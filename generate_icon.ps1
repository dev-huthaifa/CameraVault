Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

# Background: Rounded rectangle with dark sleek gradient
$rect = New-Object System.Drawing.Rectangle 0, 0, 512, 512
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point 0, 0),
    (New-Object System.Drawing.Point 512, 512),
    [System.Drawing.Color]::FromArgb(255, 15, 23, 42),
    [System.Drawing.Color]::FromArgb(255, 30, 41, 59)
)
$g.FillRectangle($bgBrush, $rect)

# Outer camera body ring
$penOuter = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 56, 189, 248), 10)
$g.DrawEllipse($penOuter, 35, 35, 442, 442)

# Lens glass base
$lensBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point 60, 60),
    (New-Object System.Drawing.Point 452, 452),
    [System.Drawing.Color]::FromArgb(255, 10, 15, 30),
    [System.Drawing.Color]::FromArgb(255, 3, 105, 161)
)
$g.FillEllipse($lensBrush, 60, 60, 392, 392)

# Inner metallic ring
$penMid = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(180, 203, 213, 225), 6)
$g.DrawEllipse($penMid, 100, 100, 312, 312)

# Deep iris
$irisBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 15, 23, 42))
$g.FillEllipse($irisBrush, 130, 130, 252, 252)

# Cyan glowing secret aperture ring
$penGlow = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 14, 165, 233), 8)
$g.DrawEllipse($penGlow, 170, 170, 172, 172)

# Golden Vault Lock Shackle & Body
$goldBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 251, 191, 36))
# Shackle (loop)
$penShackle = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 251, 191, 36), 14)
$penShackle.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$penShackle.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawArc($penShackle, 226, 200, 60, 60, 180, 180)
# Lock Body
$g.FillRectangle($goldBrush, 216, 245, 80, 65)

# Keyhole in lock
$keyBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 15, 23, 42))
$g.FillEllipse($keyBrush, 248, 258, 16, 16)
$keyPoly = @(
    (New-Object System.Drawing.Point 252, 268),
    (New-Object System.Drawing.Point 260, 268),
    (New-Object System.Drawing.Point 263, 292),
    (New-Object System.Drawing.Point 249, 292)
)
$g.FillPolygon($keyBrush, $keyPoly)

# Lens reflection highlight
$glossBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(50, 255, 255, 255))
$g.FillPie($glossBrush, 75, 75, 362, 362, 215, 55)

$g.Dispose()
$bmp.Save('S:\SecretVault\app_icon_512.png', [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Save('S:\SecretVault\icon.png', [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "ICON_SUCCESS"
