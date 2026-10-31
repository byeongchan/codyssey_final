param(
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '../당근 마켓 리전 코드/당근 마켓 리전 코드/region-data')
)
$ErrorActionPreference = 'Stop'
$sourcePath = [IO.Path]::GetFullPath($SourceDirectory)
$reportJson = Get-Content -LiteralPath (Join-Path $sourcePath 'collection-report.json') -Raw
$report = $reportJson | ConvertFrom-Json
$collectedAt = [regex]::Match($reportJson, '"collectedAt"\s*:\s*"([^"]+)"').Groups[1].Value
$records = @(Get-Content -LiteralPath (Join-Path $sourcePath 'daangn-regions.json') -Raw | ConvertFrom-Json)
if ($records.Count -ne $report.count) { throw '수집 보고서의 개수와 지역 데이터의 개수가 다릅니다.' }
if (-not $collectedAt -or -not $report.coverage) { throw '수집 보고서의 시각·범위를 확인하세요.' }
$seen = [Collections.Generic.HashSet[string]]::new()
$regions = @(foreach ($row in $records) {
    if ($row.depth -ne 3) { continue }
    $id = [string]$row.id
    if ($id -notmatch '^[0-9]{1,20}$' -or -not $row.name1 -or -not $row.name3 -or -not $seen.Add($id)) {
        throw '동네 ID·주소·중복 여부를 확인하세요.'
    }
    $province = [string]$row.name1
    $district = [string]$row.name2
    $neighborhood = [string]$row.name3
    [ordered]@{ id = $id; name = (@($province, $district, $neighborhood) | Where-Object { $_ }) -join ' '
        province = $province; district = $district; neighborhood = $neighborhood }
})
if (-not $regions.Count) { throw '사용 가능한 동네 데이터가 없습니다.' }
$actualProvinces = @($records | Group-Object name1)
foreach ($province in @($report.provinceCounts)) {
    $actual = $actualProvinces | Where-Object Name -eq $province.Name
    if (-not $actual -or $actual.Count -ne $province.Count) { throw '수집 보고서의 시·도별 개수와 데이터가 다릅니다.' }
}
$payload = [ordered]@{
    collectedAt = $collectedAt; source = [string]$report.source
    coverage = [string]$report.coverage; count = $regions.Count
    collectionSummary = [ordered]@{ reportedCount = $report.count; errors = @($report.errors).Count
        cappedQueries = @($report.cappedQueries).Count; provinceCounts = @($report.provinceCounts) }
    regions = $regions
}
$destination = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../frontend/public/data/daangn-regions.json'))
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
[IO.File]::WriteAllText($destination, ($payload | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
Write-Output ("지역 {0}개 적용: {1}" -f $regions.Count, $destination)
