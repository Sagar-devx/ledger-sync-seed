Write-Host "Compiling code..." -ForegroundColor Cyan
Remove-Item -Recurse -Force build/selfcheck -ErrorAction Ignore
New-Item -ItemType Directory -Path build/selfcheck -Force | Out-Null

# Compile using PowerShell array expansion which handles spaces perfectly
$sources = Get-ChildItem -Path src/main/java -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
javac -cp "lib/*" -d build/selfcheck $sources

Write-Host ""
Write-Host "Running SelfCheck..." -ForegroundColor Cyan
Write-Host "--------------------------------------------------" -ForegroundColor Cyan
java -cp "build/selfcheck;lib/*" in.simplifymoney.ledgersync.SelfCheck
