<#
.SYNOPSIS
    Crops, resizes and re-encodes the Mergify-Net Android screenshots used in the README.

.DESCRIPTION
    Removes the Android status bar (top) and the gesture navigation bar (bottom) from raw
    phone captures, scales them down to a README-friendly width and writes quality-tuned
    JPEGs into docs/screenshots/.

    Source captures are 1080x2340 (Galaxy S25). Only the framing changes: no pixel of the
    app UI itself is retouched.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\make-screenshots.ps1
#>

[CmdletBinding()]
param(
    [string]$SourceDir,
    [string]$OutputDir,

    # Rows removed from the top (status bar) and from the bottom (gesture bar).
    [int]$CropTop = 112,
    [int]$CropBottom = 96,

    # Width of the produced JPEGs (2x the width they are displayed at in the README).
    [int]$Width = 540,
    [int]$Quality = 90
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# $PSScriptRoot is empty while the param() block is evaluated on Windows PowerShell 5.1,
# so the defaults are resolved here, once the script is running.
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $SourceDir) { $SourceDir = Join-Path $repoRoot 'OpenCode\Photos README' }
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'docs\screenshots' }

# Source file -> output file. CropTop/CropBottom override the defaults when a capture has a
# different status-bar inset or a lot of empty background above/below the interesting part
# (notification shade, for instance). Redact lists the rectangles (source pixels) that are
# pixelated, so a screenshot never publishes the real IP addresses of the network it ran on.
$Jobs = @(
    @{ File = 'Mergify-Net_OFF.jpg';  Out = 'android-dashboard-idle.jpg' }
    @{ File = 'Mergify-Net_ON1.jpg';  Out = 'android-dashboard-active.jpg'
       Redact = @(
           @{ X = 156; Y = 1434; W = 754; H = 38 }   # Wi-Fi IPv4/IPv6
           @{ X = 156; Y = 1891; W = 754; H = 38 }   # Cell IPv4/IPv6
       ) }
    @{ File = 'Mergify-Net_ON2.jpg';  Out = 'android-settings.jpg' }
    @{ File = 'Notification.jpg';     Out = 'android-notification.jpg'; CropTop = 200; CropBottom = 1540 }
    # Third-party Speedtest capture: its gesture bar sits higher than on the app screens, so the
    # whole tab bar is dropped rather than leaving it half-cropped.
    @{ File = 'Speedtest.jpg';        Out = 'performance-speedtest.jpg'; CropBottom = 200 }
)

function Set-Mosaic {
    param(
        [System.Drawing.Bitmap]$Bitmap,
        [int]$X,
        [int]$Y,
        [int]$W,
        [int]$H,
        [int]$BlockSize = 24
    )

    # Downscale the area to a handful of pixels, then put it back without smoothing: the text
    # is unreadable, the layout and colours of the card stay exactly the same.
    $smallWidth = [math]::Max(1, [int][math]::Ceiling($W / $BlockSize))
    $smallHeight = [math]::Max(1, [int][math]::Ceiling($H / $BlockSize))
    $small = New-Object System.Drawing.Bitmap($smallWidth, $smallHeight)

    $g = [System.Drawing.Graphics]::FromImage($small)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBilinear
    $g.DrawImage($Bitmap,
        (New-Object System.Drawing.Rectangle(0, 0, $smallWidth, $smallHeight)),
        (New-Object System.Drawing.Rectangle($X, $Y, $W, $H)),
        [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()

    $g2 = [System.Drawing.Graphics]::FromImage($Bitmap)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g2.DrawImage($small, (New-Object System.Drawing.Rectangle($X, $Y, $W, $H)))
    $g2.Dispose()
    $small.Dispose()
}

function New-Screenshot {
    param(
        [string]$InPath,
        [string]$OutPath,
        [int]$Top,
        [int]$Bottom,
        [int]$TargetWidth,
        [int]$JpegQuality,
        [object[]]$Redact
    )

    $src = [System.Drawing.Image]::FromFile($InPath)
    try {
        $rectHeight = $src.Height - $Top - $Bottom
        if ($rectHeight -le 0) {
            throw "Crop removes the whole image: $InPath"
        }

        # 1. Crop: drop the status bar and the navigation bar.
        $rect = New-Object System.Drawing.Rectangle(0, $Top, $src.Width, $rectHeight)
        $crop = New-Object System.Drawing.Bitmap($src.Width, $rectHeight, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $g = [System.Drawing.Graphics]::FromImage($crop)
        $g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $rect.Width, $rect.Height)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()

        # 1b. Pixelate the areas that must not be published (real IP addresses).
        foreach ($area in $Redact) {
            $y = [int]$area.Y - $Top
            if ($y -lt 0) { $y = 0 }
            Set-Mosaic -Bitmap $crop -X ([int]$area.X) -Y $y -W ([int]$area.W) -H ([int]$area.H)
        }

        # 2. Resize with high-quality interpolation.
        $targetHeight = [int][math]::Round($rectHeight * $TargetWidth / $src.Width)
        $out = New-Object System.Drawing.Bitmap($TargetWidth, $targetHeight, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $g2 = [System.Drawing.Graphics]::FromImage($out)
        $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g2.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g2.DrawImage($crop, 0, 0, $TargetWidth, $targetHeight)
        $g2.Dispose()
        $crop.Dispose()

        # 3. Save as progressive-friendly JPEG at the requested quality.
        $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
            Where-Object { $_.MimeType -eq 'image/jpeg' }
        $params = New-Object System.Drawing.Imaging.EncoderParameters(1)
        $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
            [System.Drawing.Imaging.Encoder]::Quality, [long]$JpegQuality)
        $out.Save($OutPath, $codec, $params)
        $out.Dispose()

        return $targetHeight
    }
    finally {
        $src.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "Screenshot source folder not found: $SourceDir"
}
if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$sourceBefore = Get-ChildItem -LiteralPath $SourceDir -Filter *.jpg | Measure-Object -Property Length -Sum
$totalAfter = 0

foreach ($job in $Jobs) {
    $inPath = Join-Path $SourceDir $job.File
    if (-not (Test-Path -LiteralPath $inPath)) {
        throw "Missing source screenshot: $inPath"
    }
    $outPath = Join-Path $OutputDir $job.Out
    $top = if ($job.ContainsKey('CropTop')) { [int]$job.CropTop } else { $CropTop }
    $bottom = if ($job.ContainsKey('CropBottom')) { [int]$job.CropBottom } else { $CropBottom }
    $height = New-Screenshot -InPath $inPath -OutPath $outPath -Top $top -Bottom $bottom -TargetWidth $Width -JpegQuality $Quality -Redact $job.Redact
    $size = (Get-Item -LiteralPath $outPath).Length
    $totalAfter += $size
    Write-Host ("{0,-28} -> {1,-30} {2}x{3}  {4,6:N0} Ko" -f $job.File, $job.Out, $Width, $height, ($size / 1KB))
}

Write-Host ''
Write-Host ("Sources: {0:N0} Ko  ->  docs/screenshots: {1:N0} Ko" -f ($sourceBefore.Sum / 1KB), ($totalAfter / 1KB))

