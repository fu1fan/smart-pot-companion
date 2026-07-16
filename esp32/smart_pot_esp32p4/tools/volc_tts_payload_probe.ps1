param(
    [string]$Sdkconfig = "D:\AAAesp32\smart_pot_esp32p4\sdkconfig"
)

$ErrorActionPreference = 'Stop'

function Read-ConfigString([string]$name) {
    $line = Select-String -LiteralPath $Sdkconfig -Pattern ("^CONFIG_" + $name + '="(.*)"$') | Select-Object -First 1
    if (-not $line) { throw "Missing CONFIG_$name" }
    return $line.Matches[0].Groups[1].Value
}

function Write-I32BE([IO.MemoryStream]$stream, [int]$value) {
    $bytes = [BitConverter]::GetBytes([Net.IPAddress]::HostToNetworkOrder($value))
    $stream.Write($bytes, 0, 4)
}

function Read-I32BE([byte[]]$bytes, [ref]$offset) {
    $tmp = [byte[]]::new(4)
    [Array]::Copy($bytes, $offset.Value, $tmp, 0, 4)
    $offset.Value += 4
    return [Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($tmp, 0))
}

function New-EventFrame([int]$event, [string]$sessionId, [byte[]]$payload) {
    $stream = [IO.MemoryStream]::new()
    $stream.WriteByte(0x11)
    $stream.WriteByte(0x14)
    $stream.WriteByte(0x10)
    $stream.WriteByte(0x00)
    Write-I32BE $stream $event
    if ($event -notin @(1, 2)) {
        $sid = [Text.Encoding]::UTF8.GetBytes($sessionId)
        Write-I32BE $stream $sid.Length
        $stream.Write($sid, 0, $sid.Length)
    }
    Write-I32BE $stream $payload.Length
    $stream.Write($payload, 0, $payload.Length)
    return $stream.ToArray()
}

function Send-Binary([Net.WebSockets.ClientWebSocket]$ws, [byte[]]$bytes) {
    $segment = [ArraySegment[byte]]::new($bytes)
    $ws.SendAsync($segment, [Net.WebSockets.WebSocketMessageType]::Binary, $true,
                  [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function Receive-Frame([Net.WebSockets.ClientWebSocket]$ws, [int]$timeoutMs = 10000) {
    $buffer = [byte[]]::new(65536)
    $stream = [IO.MemoryStream]::new()
    $cts = [Threading.CancellationTokenSource]::new($timeoutMs)
    try {
        do {
            $seg = [ArraySegment[byte]]::new($buffer)
            $result = $ws.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
            if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { return $null }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
    } finally {
        $cts.Dispose()
    }
    $bytes = $stream.ToArray()
    $offset = [ref]4
    $type = $bytes[1] -shr 4
    $flag = $bytes[1] -band 0x0f
    $event = 0
    $error = 0
    if ($type -eq 15) { $error = Read-I32BE $bytes $offset }
    if ($flag -eq 4) {
        $event = Read-I32BE $bytes $offset
        if ($event -notin @(1,2,50,51,52)) {
            $sidLen = Read-I32BE $bytes $offset
            $offset.Value += $sidLen
        }
        if ($event -in @(50,51,52)) {
            $cidLen = Read-I32BE $bytes $offset
            $offset.Value += $cidLen
        }
    }
    $payloadLen = Read-I32BE $bytes $offset
    $payload = [byte[]]::new($payloadLen)
    if ($payloadLen -gt 0) { [Array]::Copy($bytes, $offset.Value, $payload, 0, $payloadLen) }
    return [pscustomobject]@{ Type=$type; Event=$event; Error=$error; Payload=$payload }
}

function Wait-Event($ws, [int]$wanted) {
    while ($true) {
        $frame = Receive-Frame $ws
        if ($null -eq $frame) { throw 'WebSocket closed' }
        if ($frame.Type -eq 15) {
            throw "Protocol error $($frame.Error): $([Text.Encoding]::UTF8.GetString($frame.Payload))"
        }
        if ($frame.Event -eq $wanted) { return $frame }
    }
}

$apiKey = Read-ConfigString 'SMART_POT_VOLC_API_KEY'
$resource = Read-ConfigString 'SMART_POT_VOLC_TTS_RESOURCE_ID'
$speaker = Read-ConfigString 'SMART_POT_VOLC_TTS_SPEAKER'
$endpoint = Read-ConfigString 'SMART_POT_TTS_ENDPOINT'
$text = -join @([char]0x4f60,[char]0x597d,[char]0xff0c,[char]0x6211,
                 [char]0x662f,[char]0x5c0f,[char]0x9ea6,[char]0x3002)
$quotedText = ConvertTo-Json $text -Compress
$variants = [ordered]@{
    top_level = '{"text":' + $quotedText + '}'
    req_params = '{"req_params":{"text":' + $quotedText + '}}'
    namespace_req_params = '{"namespace":"BidirectionalTTS","req_params":{"text":' + $quotedText + '}}'
}

foreach ($entry in $variants.GetEnumerator()) {
    $ws = [Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.SetRequestHeader('X-Api-Key', $apiKey)
    $ws.Options.SetRequestHeader('X-Api-Resource-Id', $resource)
    $ws.Options.SetRequestHeader('X-Api-Connect-Id', [guid]::NewGuid().ToString())
    $ws.ConnectAsync([uri]$endpoint, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    Send-Binary $ws (New-EventFrame 1 '' ([Text.Encoding]::UTF8.GetBytes('{}')))
    [void](Wait-Event $ws 50)
    $session = [guid]::NewGuid().ToString()
    $start = @{
        'namespace' = 'BidirectionalTTS'
        user = @{ uid = 'smart-pot-pc-probe' }
        req_params = @{
            speaker = $speaker
            audio_params = @{ format = 'pcm'; sample_rate = 24000; speech_rate = 5; loudness_rate = 0 }
        }
    } | ConvertTo-Json -Depth 8 -Compress
    Send-Binary $ws (New-EventFrame 100 $session ([Text.Encoding]::UTF8.GetBytes($start)))
    [void](Wait-Event $ws 150)
    $task = [string]$entry.Value
    Send-Binary $ws (New-EventFrame 200 $session ([Text.Encoding]::UTF8.GetBytes($task)))
    Start-Sleep -Milliseconds 800
    Send-Binary $ws (New-EventFrame 102 $session ([Text.Encoding]::UTF8.GetBytes('{}')))
    $audioBytes = 0
    $events = [Collections.Generic.List[int]]::new()
    while ($true) {
        $frame = Receive-Frame $ws
        if ($null -eq $frame) { break }
        $events.Add($frame.Event)
        if ($frame.Event -eq 352) { $audioBytes += $frame.Payload.Length }
        if ($frame.Type -eq 15 -or $frame.Event -in @(152,153)) { break }
    }
    Write-Output ("VARIANT={0} AUDIO_BYTES={1} EVENTS={2}" -f $entry.Key,$audioBytes,($events -join ','))
    try { Send-Binary $ws (New-EventFrame 2 '' ([Text.Encoding]::UTF8.GetBytes('{}'))) } catch {}
    $ws.Dispose()
}
