param(
    [string]$Sdkconfig = (Join-Path (Split-Path $PSScriptRoot -Parent) 'sdkconfig'),
    [string]$OutputDir = (Join-Path (Split-Path $PSScriptRoot -Parent) 'offline_audio')
)

$ErrorActionPreference = 'Stop'

$prompts = [ordered]@{
    e00a = @{ Text = '又冷又干……感觉我在南极流浪……'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e00b = @{ Text = '没光没水，我选择躺平。等我变成了干柴，主人记得拿我去烧火取暖……'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e01  = @{ Text = '我掐指一算，我上辈子可能是一块海绵宝宝……但现在，我干得连蟹堡王都捏不出来了！'; Rate = -5; Pitch = 1; Style = '请用有点委屈、担心、像小植物撒娇的语气说话。' }
    e02  = @{ Text = '又干又晒……我是不是在铁板烧上？主人！撒点孜然就能上桌了！'; Rate = 15; Pitch = 2; Style = '请用着急、慌张但仍然可爱的语气说话。' }
    e03a = @{ Text = '好黑呀，我是不是要长蘑菇了？'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e03b = @{ Text = '主人，能带我去晒晒太阳吗？我想光合作用！'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e04  = @{ Text = '主人！我宣布！你被评为本月最佳铲屎官！奖励你摸摸我的新叶子！'; Rate = 10; Pitch = 2; Style = '请用开心、活泼、亲切、略带雀跃的语气说话。' }
    e05  = @{ Text = '哎呀，我要被晒干了！主人给我打把伞。'; Rate = 15; Pitch = 2; Style = '请用着急、慌张但仍然可爱的语气说话。' }
    e06a = @{ Text = '我感觉自己像被泡发的木耳，又冷又潮，再泡下去我就要长蘑菇了！'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e06b = @{ Text = '又湿又暗……这不是梅雨季吗？我是不是该给自己贴个除湿袋了？我好潮啊！'; Rate = -15; Pitch = -2; Style = '请用困倦、低落、稍微慢一点的语气说话。' }
    e07  = @{ Text = '我好像泡在游泳池里了……脚脚有点闷。'; Rate = -5; Pitch = 1; Style = '请用有点委屈、担心、像小植物撒娇的语气说话。' }
    e08  = @{ Text = '又涝又热……我觉得我的人生已经到达了火山口的顶峰！'; Rate = 15; Pitch = 2; Style = '请用着急、慌张但仍然可爱的语气说话。' }
}

function Read-ConfigString([string]$Name) {
    $line = Select-String -LiteralPath $Sdkconfig -Pattern ("^CONFIG_" + $Name + '="(.*)"$') | Select-Object -First 1
    if (-not $line) { throw "Missing CONFIG_$Name in $Sdkconfig" }
    return $line.Matches[0].Groups[1].Value
}

function Read-ConfigInt([string]$Name) {
    $line = Select-String -LiteralPath $Sdkconfig -Pattern ("^CONFIG_" + $Name + '=(\d+)$') | Select-Object -First 1
    if (-not $line) { throw "Missing numeric CONFIG_$Name in $Sdkconfig" }
    return [int]$line.Matches[0].Groups[1].Value
}

function Write-I32BE([IO.Stream]$Stream, [int]$Value) {
    $bytes = [BitConverter]::GetBytes([Net.IPAddress]::HostToNetworkOrder($Value))
    $Stream.Write($bytes, 0, 4)
}

function Read-I32BE([byte[]]$Bytes, [ref]$Offset) {
    $tmp = [byte[]]::new(4)
    [Array]::Copy($Bytes, $Offset.Value, $tmp, 0, 4)
    $Offset.Value += 4
    return [Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($tmp, 0))
}

function New-EventFrame([int]$Event, [string]$SessionId, [byte[]]$Payload) {
    $stream = [IO.MemoryStream]::new()
    $stream.WriteByte(0x11)
    $stream.WriteByte(0x14)
    $stream.WriteByte(0x10)
    $stream.WriteByte(0x00)
    Write-I32BE $stream $Event
    if ($Event -notin @(1, 2)) {
        $sessionBytes = [Text.Encoding]::UTF8.GetBytes($SessionId)
        Write-I32BE $stream $sessionBytes.Length
        $stream.Write($sessionBytes, 0, $sessionBytes.Length)
    }
    Write-I32BE $stream $Payload.Length
    $stream.Write($Payload, 0, $Payload.Length)
    return $stream.ToArray()
}

function Send-Binary([Net.WebSockets.ClientWebSocket]$Socket, [byte[]]$Bytes) {
    $segment = [ArraySegment[byte]]::new($Bytes)
    [void]$Socket.SendAsync($segment, [Net.WebSockets.WebSocketMessageType]::Binary, $true,
                           [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function Receive-Frame([Net.WebSockets.ClientWebSocket]$Socket, [int]$TimeoutMs = 30000) {
    $buffer = [byte[]]::new(65536)
    $stream = [IO.MemoryStream]::new()
    $cts = [Threading.CancellationTokenSource]::new($TimeoutMs)
    try {
        do {
            $segment = [ArraySegment[byte]]::new($buffer)
            $result = $Socket.ReceiveAsync($segment, $cts.Token).GetAwaiter().GetResult()
            if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { return $null }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
    } finally {
        $cts.Dispose()
    }

    $bytes = $stream.ToArray()
    if ($bytes.Length -lt 12) { throw "Short TTS frame: $($bytes.Length) bytes" }
    $offset = [ref]4
    $type = $bytes[1] -shr 4
    $flags = $bytes[1] -band 0x0f
    $event = 0
    $error = 0
    if ($type -eq 15) { $error = Read-I32BE $bytes $offset }
    if ($flags -eq 4) {
        $event = Read-I32BE $bytes $offset
        if ($event -notin @(1, 2, 50, 51, 52)) {
            $sessionLength = Read-I32BE $bytes $offset
            $offset.Value += $sessionLength
        }
        if ($event -in @(50, 51, 52)) {
            $connectLength = Read-I32BE $bytes $offset
            $offset.Value += $connectLength
        }
    }
    $payloadLength = Read-I32BE $bytes $offset
    $payload = [byte[]]::new([Math]::Max(0, $payloadLength))
    if ($payloadLength -gt 0) { [Array]::Copy($bytes, $offset.Value, $payload, 0, $payloadLength) }
    return [pscustomobject]@{ Type = $type; Event = $event; Error = $error; Payload = $payload }
}

function Wait-Event([Net.WebSockets.ClientWebSocket]$Socket, [int]$Wanted) {
    while ($true) {
        $frame = Receive-Frame $Socket
        if ($null -eq $frame) { throw 'TTS WebSocket closed unexpectedly' }
        if ($frame.Type -eq 15) {
            throw "TTS protocol error $($frame.Error): $([Text.Encoding]::UTF8.GetString($frame.Payload))"
        }
        if ($frame.Event -eq $Wanted) { return $frame }
    }
}

function Write-PcmWav([string]$Path, [byte[]]$Pcm, [int]$SampleRate) {
    $stream = [IO.File]::Create($Path)
    $writer = [IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
        $writer.Write([int](36 + $Pcm.Length))
        $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
        $writer.Write([int]16)
        $writer.Write([int16]1)
        $writer.Write([int16]1)
        $writer.Write([int]$SampleRate)
        $writer.Write([int]($SampleRate * 2))
        $writer.Write([int16]2)
        $writer.Write([int16]16)
        $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([int]$Pcm.Length)
        $writer.Write($Pcm)
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Invoke-VolcTts([hashtable]$Prompt, [string]$Stem, [string]$ApiKey,
                        [string]$Resource, [string]$Speaker, [string]$Endpoint,
                        [int]$SampleRate) {
    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $audio = [IO.MemoryStream]::new()
    try {
        $socket.Options.SetRequestHeader('X-Api-Key', $ApiKey)
        $socket.Options.SetRequestHeader('X-Api-Resource-Id', $Resource)
        $socket.Options.SetRequestHeader('X-Api-Connect-Id', [guid]::NewGuid().ToString())
        [void]$socket.ConnectAsync([uri]$Endpoint, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        Send-Binary $socket (New-EventFrame 1 '' ([Text.Encoding]::UTF8.GetBytes('{}')))
        [void](Wait-Event $socket 50)

        $session = [guid]::NewGuid().ToString()
        $start = @{
            namespace = 'BidirectionalTTS'
            user = @{ uid = 'smart-pot-offline-generator' }
            req_params = @{
                speaker = $Speaker
                audio_params = @{
                    format = 'pcm'; sample_rate = $SampleRate
                    speech_rate = [int]$Prompt.Rate; loudness_rate = 0
                }
                post_process = @{ pitch = [int]$Prompt.Pitch }
                context_texts = @([string]$Prompt.Style)
            }
        } | ConvertTo-Json -Depth 8 -Compress
        Send-Binary $socket (New-EventFrame 100 $session ([Text.Encoding]::UTF8.GetBytes($start)))
        [void](Wait-Event $socket 150)

        $task = @{ namespace = 'BidirectionalTTS'; req_params = @{ text = [string]$Prompt.Text } } |
                ConvertTo-Json -Depth 4 -Compress
        Send-Binary $socket (New-EventFrame 200 $session ([Text.Encoding]::UTF8.GetBytes($task)))
        Send-Binary $socket (New-EventFrame 102 $session ([Text.Encoding]::UTF8.GetBytes('{}')))
        while ($true) {
            $frame = Receive-Frame $socket
            if ($null -eq $frame) { break }
            if ($frame.Type -eq 15) {
                throw "TTS protocol error $($frame.Error): $([Text.Encoding]::UTF8.GetString($frame.Payload))"
            }
            if ($frame.Event -eq 352 -and $frame.Payload.Length -gt 0) {
                $audio.Write($frame.Payload, 0, $frame.Payload.Length)
            }
            if ($frame.Event -in @(152, 153)) { break }
        }
        if ($audio.Length -eq 0) { throw "No PCM received for $Stem" }
        Write-PcmWav (Join-Path $OutputDir "$Stem.wav") $audio.ToArray() $SampleRate
        Write-Host "$Stem.wav: $($audio.Length) PCM bytes"
    } finally {
        try { Send-Binary $socket (New-EventFrame 2 '' ([Text.Encoding]::UTF8.GetBytes('{}'))) } catch {}
        $audio.Dispose()
        $socket.Dispose()
    }
}

$apiKey = Read-ConfigString 'SMART_POT_VOLC_API_KEY'
$resource = Read-ConfigString 'SMART_POT_VOLC_TTS_RESOURCE_ID'
$speaker = Read-ConfigString 'SMART_POT_VOLC_TTS_SPEAKER'
$endpoint = Read-ConfigString 'SMART_POT_TTS_ENDPOINT'
$sampleRate = Read-ConfigInt 'SMART_POT_VOLC_TTS_SAMPLE_RATE'
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'Volcengine API key is empty' }
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

foreach ($entry in $prompts.GetEnumerator()) {
    Invoke-VolcTts $entry.Value $entry.Key $apiKey $resource $speaker $endpoint $sampleRate
}
