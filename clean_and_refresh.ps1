# Remove old and corrupted apk/aab files
Get-ChildItem -Path "S:\SecretVault" -File | Where-Object {
    ($_.Extension -in @(".apk", ".aab")) -and ($_.Name -ne "CameraVault_v1.0_Signed.apk" -and $_.Name -ne "CameraVault_v1.0_Signed.aab")
} | Remove-Item -Force -ErrorAction SilentlyContinue

# Create a clean dedicated folder
$releaseDir = "S:\SecretVault\FINAL_RELEASE_APK"
if (-not (Test-Path $releaseDir)) {
    New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
}

# Copy the latest release build
$sourceApk = "S:\SecretVault\app\build\outputs\apk\release\app-release.apk"
$sourceAab = "S:\SecretVault\app\build\outputs\bundle\release\app-release.aab"

$targetApk1 = "S:\SecretVault\CameraVault_v1.0_Signed.apk"
$targetAab1 = "S:\SecretVault\CameraVault_v1.0_Signed.aab"
$targetApk2 = Join-Path $releaseDir "CameraVault_v1.0_Signed.apk"
$targetAab2 = Join-Path $releaseDir "CameraVault_v1.0_Signed.aab"

Copy-Item $sourceApk -Destination $targetApk1 -Force
Copy-Item $sourceAab -Destination $targetAab1 -Force
Copy-Item $sourceApk -Destination $targetApk2 -Force
Copy-Item $sourceAab -Destination $targetAab2 -Force

# Set timestamp to current exact system time
$now = Get-Date
(Get-Item $targetApk1).LastWriteTime = $now
(Get-Item $targetAab1).LastWriteTime = $now
(Get-Item $targetApk2).LastWriteTime = $now
(Get-Item $targetAab2).LastWriteTime = $now

Write-Host "CLEANUP_SUCCESS"
