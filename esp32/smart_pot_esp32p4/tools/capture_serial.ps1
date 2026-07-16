param(
    [string]$PortName = "COM8",
    [int]$BaudRate = 115200,
    [int]$DurationSeconds = 300,
    [string]$OutputPath = "build\serial_capture.log"
)

$port = New-Object System.IO.Ports.SerialPort $PortName, $BaudRate, "None", 8, "One"
$port.ReadTimeout = 300
$port.Encoding = New-Object System.Text.UTF8Encoding $false
$deadline = (Get-Date).AddSeconds($DurationSeconds)

try {
    Add-Content -LiteralPath $OutputPath -Value "CAPTURE_START $(Get-Date -Format o) $PortName"
    $port.Open()
    Add-Content -LiteralPath $OutputPath -Value "CAPTURE_OPENED $(Get-Date -Format o) $PortName"
    while ((Get-Date) -lt $deadline) {
        try {
            $line = $port.ReadLine()
            Add-Content -LiteralPath $OutputPath -Value $line
        }
        catch [System.TimeoutException] {
        }
    }
}
catch {
    Add-Content -LiteralPath $OutputPath -Value "CAPTURE_ERROR $(Get-Date -Format o) $($_.Exception.Message)"
    throw
}
finally {
    if ($port.IsOpen) {
        $port.Close()
    }
}
