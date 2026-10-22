[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Path)

# Plain dotenv values only. Never evaluate file contents or print secret values.
$taskEnvPath = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
$taskEnvLines = [System.IO.File]::ReadAllLines($taskEnvPath, [System.Text.Encoding]::UTF8)
$taskEnvLineNumber = 0
foreach ($taskEnvLine in $taskEnvLines) {
    $taskEnvLineNumber++
    if ($taskEnvLine -match '^\s*(#.*)?$') { continue }
    if ($taskEnvLine -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        throw "Invalid dotenv format at line $taskEnvLineNumber. Use NAME=value."
    }
    $taskEnvName = $Matches[1]
    $taskEnvValue = $Matches[2].Trim()
    if ($taskEnvName -in @('HOME', 'CODEX_HOME')) {
        throw "Reserved environment variable at line $taskEnvLineNumber."
    }
    if ($taskEnvValue.Length -ge 2 -and (
        ($taskEnvValue.StartsWith('"') -and $taskEnvValue.EndsWith('"')) -or
        ($taskEnvValue.StartsWith("'") -and $taskEnvValue.EndsWith("'"))
    )) {
        $taskEnvValue = $taskEnvValue.Substring(1, $taskEnvValue.Length - 2)
    }
    [System.Environment]::SetEnvironmentVariable($taskEnvName, $taskEnvValue, 'Process')
}
