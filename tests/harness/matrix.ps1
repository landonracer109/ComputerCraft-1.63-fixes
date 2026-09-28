# Headless benchmark matrix (Windows PowerShell): every scheduler setting x workload x load,
# 24 real ComputerCraft computers, 30 s per run, with the CPU's actual average clock per run.
#
#   powershell -ExecutionPolicy Bypass -File tests\harness\matrix.ps1 -Jar build\ComputerCraft1.63+tomo1+fixes-parallel.jar -Java C:\path\to\java.exe
#
# Needs tools\mc-1.6.4-srg.jar and tools\forge-srg.jar (see BUILDING.md) and HeadlessCC.class compiled
# into tests\harness against the jar (see PARALLEL.md). Results go to tests\harness\matrix.txt.
param(
  [Parameter(Mandatory = $true)][string]$Jar,
  [string]$Java = "java",
  [int]$Seconds = 30,
  [int]$Computers = 24
)
$ErrorActionPreference = "Continue"
$root = Resolve-Path "$PSScriptRoot\..\.."
$dir  = "$root\tests\harness"
$Jar  = (Resolve-Path $Jar).Path
$cp   = "$dir;$Jar;$root\tools\mc-1.6.4-srg.jar;$root\tools\forge-srg.jar"
$out  = "$dir\matrix.txt"
$cpu  = Get-CimInstance Win32_Processor | Select-Object -First 1
$baseMHz = $cpu.MaxClockSpeed
$modes = @(
  @{ name = "original";     args = @() },
  @{ name = "reuse worker"; args = @("-Dcc.reuseWorker=true") },
  @{ name = "2 threads";    args = @("-Dcc.threads=2") },
  @{ name = "4 threads";    args = @("-Dcc.threads=4") },
  @{ name = "6 threads";    args = @("-Dcc.threads=6") },
  @{ name = "8 threads";    args = @("-Dcc.threads=8") },
  @{ name = "12 threads";   args = @("-Dcc.threads=12") },
  @{ name = "16 threads";   args = @("-Dcc.threads=16") }
)
"# CPU: $($cpu.Name.Trim()), $($cpu.NumberOfCores) cores / $($cpu.NumberOfLogicalProcessors) threads, $baseMHz MHz base" | Out-File -Encoding ascii $out
"mode|program|computers|units|events_per_s|per_computer|min|max|ok|wrong|missing|avg_mhz" | Out-File -Append -Encoding ascii $out
foreach ($program in @("compute", "dash")) {
  foreach ($units in @(10000, 20000, 40000)) {
    foreach ($m in $modes) {
      $work = "$env:TEMP\cc-matrix\" + ($m.name -replace ' ', '_') + "_$program`_$units"
      if (Test-Path $work) { Remove-Item -Recurse -Force $work }
      $csv = "$env:TEMP\cc-matrix-perf.csv"
      if (Test-Path $csv) { Remove-Item -Force $csv }
      # "% Processor Performance" x base clock = actual average clock during the run
      $perf = Start-Process -FilePath typeperf -ArgumentList @('"\Processor Information(_Total)\% Processor Performance"', '-si', '1', '-sc', ($Seconds + 2), '-o', $csv, '-y') -WindowStyle Hidden -PassThru
      $jargs = $m.args + @("-cp", $cp, "HeadlessCC", $Jar, $work, "$Computers", "$Seconds", "$units", $program)
      $res = & $Java @jargs 2>&1 | Where-Object { $_ -like "RESULT*" -or $_ -like "*checksum*" -or $_ -like "MISSING*" }
      $perf.WaitForExit()
      $mhz = 0
      if (Test-Path $csv) {
        $vals = Import-Csv $csv | ForEach-Object { $_.PSObject.Properties.Value[1] } | Where-Object { $_ -match '^[0-9.]+$' } | ForEach-Object { [double]$_ }
        if ($vals.Count -gt 0) { $mhz = [math]::Round(($vals | Measure-Object -Average).Average * $baseMHz / 100) }
      }
      foreach ($line in $res) {
        if ($line -like "RESULT*") { ($line -replace '^RESULT\|', '') + "|$mhz" | Out-File -Append -Encoding ascii $out }
        else { "# $line" | Out-File -Append -Encoding ascii $out }
      }
    }
  }
}
"DONE" | Out-File -Append -Encoding ascii $out
