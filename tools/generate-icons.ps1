<#
.SYNOPSIS
  Renders the app icon (same artwork as assets/icon/icon.svg) into every format the build needs.

.DESCRIPTION
  Produces: assets/icon/icon-1024.png, the in-app drawable, desktop icons (png/ico/icns)
  and the legacy Android launcher PNGs. Uses System.Drawing, so run it on Windows:
    pwsh tools/generate-icons.ps1
#>
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent

function New-RoundedRect([float]$X, [float]$Y, [float]$W, [float]$H, [float]$R) {
    $d = $R * 2
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($X, $Y, $d, $d, 180, 90)
    $p.AddArc($X + $W - $d, $Y, $d, $d, 270, 90)
    $p.AddArc($X + $W - $d, $Y + $H - $d, $d, $d, 0, 90)
    $p.AddArc($X, $Y + $H - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

# Shape: 'square' (rounded square), 'round' (circle), 'bleed' (opaque full square, for iOS). Inset: transparent margin, in 1024-grid units.
function New-Icon([int]$Size, [string]$Shape = 'square', [float]$Inset = 0) {
    # iOS app icons must be opaque and square: the OS applies its own rounded mask.
    $pixelFormat = [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    if ($Shape -eq "bleed") { $pixelFormat = [System.Drawing.Imaging.PixelFormat]::Format24bppRgb }
    $bmp = New-Object System.Drawing.Bitmap $Size, $Size, $pixelFormat
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.ScaleTransform($Size / 1024.0, $Size / 1024.0)
    $g.TranslateTransform($Inset, $Inset)
    $s = (1024.0 - 2 * $Inset) / 1024.0
    $g.ScaleTransform($s, $s)

    # Background gradient (violet -> blue)
    $c1 = [System.Drawing.Color]::FromArgb(255, 0x7C, 0x3A, 0xED)
    $c2 = [System.Drawing.Color]::FromArgb(255, 0x25, 0x63, 0xEB)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.PointF]::new(0, 0)), ([System.Drawing.PointF]::new(1024, 1024)), $c1, $c2
    if ($Shape -eq "round") {
        $g.FillEllipse($brush, 0, 0, 1024, 1024)
    } elseif ($Shape -eq "bleed") {
        $g.FillRectangle($brush, 0, 0, 1024, 1024)
    } else {
        $bg = New-RoundedRect 0 0 1024 1024 224
        $g.FillPath($brush, $bg)
    }

    # Monitor, stand and base
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 44
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'; $pen.LineJoin = 'Round'
    $g.DrawPath($pen, (New-RoundedRect 212 262 600 400 48))
    $g.DrawLine($pen, 512, 662, 512, 742)
    $g.DrawLine($pen, 392, 762, 632, 762)

    # Play triangle
    $tri = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(450, 365),
        [System.Drawing.PointF]::new(450, 555),
        [System.Drawing.PointF]::new(610, 460))
    $g.FillPolygon([System.Drawing.Brushes]::White, $tri)
    $triPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 20
    $triPen.LineJoin = 'Round'
    $g.DrawPolygon($triPen, $tri)

    $g.Dispose()
    return $bmp
}

function Get-PngBytes($bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    return , $ms.ToArray()
}

function Save-Png($bmp, [string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    [System.IO.File]::WriteAllBytes($path, (Get-PngBytes $bmp))
    Write-Host "wrote $RelativePath"
}

function Write-BigEndian32([System.IO.BinaryWriter]$w, [uint32]$v) {
    $w.Write([byte[]]@((($v -shr 24) -band 255), (($v -shr 16) -band 255), (($v -shr 8) -band 255), ($v -band 255)))
}

# --- Source + in-app drawable -------------------------------------------------
Save-Png (New-Icon 1024) 'assets/icon/icon-1024.png'
Save-Png (New-Icon 512) 'shared/src/commonMain/composeResources/drawable/app_icon.png'

# --- Desktop: png / ico / icns ------------------------------------------------
Save-Png (New-Icon 512) 'desktopApp/icons/icon.png'

$icoSizes = 16, 32, 48, 64, 128, 256
$icoPngs = $icoSizes | ForEach-Object { , (Get-PngBytes (New-Icon $_)) }
$ms = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $ms
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$icoSizes.Count)
$offset = 6 + 16 * $icoSizes.Count
for ($i = 0; $i -lt $icoSizes.Count; $i++) {
    $dim = $icoSizes[$i]
    if ($dim -ge 256) { $dim = 0 }   # 0 means 256 in the ICO format
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$icoPngs[$i].Length); $w.Write([uint32]$offset)
    $offset += $icoPngs[$i].Length
}
foreach ($png in $icoPngs) { $w.Write([byte[]]$png) }
[System.IO.File]::WriteAllBytes((Join-Path $root 'desktopApp/icons/icon.ico'), $ms.ToArray())
Write-Host 'wrote desktopApp/icons/icon.ico'

# macOS icons keep a transparent margin (Apple's template uses an 824px body inside 1024px).
$icnsTypes = [ordered]@{ 'ic11' = 32; 'ic12' = 64; 'ic07' = 128; 'ic08' = 256; 'ic09' = 512; 'ic10' = 1024 }
$body = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $body
foreach ($type in $icnsTypes.Keys) {
    $png = Get-PngBytes (New-Icon $icnsTypes[$type] 'square' 100)
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes($type))
    Write-BigEndian32 $bw ([uint32]($png.Length + 8))
    $bw.Write([byte[]]$png)
}
$out = New-Object System.IO.MemoryStream
$ow = New-Object System.IO.BinaryWriter $out
$ow.Write([System.Text.Encoding]::ASCII.GetBytes('icns'))
Write-BigEndian32 $ow ([uint32]($body.Length + 8))
$ow.Write($body.ToArray())
[System.IO.File]::WriteAllBytes((Join-Path $root 'desktopApp/icons/icon.icns'), $out.ToArray())
Write-Host 'wrote desktopApp/icons/icon.icns'

# --- Android legacy launcher icons (API < 26; newer versions use the adaptive XML) ---
$densities = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($d in $densities.Keys) {
    $dir = "androidApp/src/main/res/mipmap-$d"
    Save-Png (New-Icon $densities[$d]) "$dir/ic_launcher.png"
    Save-Png (New-Icon $densities[$d] 'round') "$dir/ic_launcher_round.png"
}

# --- Web: favicon + installable-app icons ----------------------------------------
Save-Png (New-Icon 64) 'webApp/src/wasmJsMain/resources/favicon.png'
Save-Png (New-Icon 192) 'webApp/src/wasmJsMain/resources/icon-192.png'
Save-Png (New-Icon 512) 'webApp/src/wasmJsMain/resources/icon-512.png'

# --- iOS: single 1024px app icon (Xcode derives every other size) ----------------
Save-Png (New-Icon 1024 'bleed') 'iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png'
