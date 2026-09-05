[CmdletBinding()]
param(
    [int[]]$RecordCounts = @(2000, 10000, 25000),
    [ValidateRange(2, 20)] [int]$Repetitions = 5,
    [string]$PythonExe = "python"
)

$ErrorActionPreference = "Stop"
$benchmarkRoot = $PSScriptRoot
$composeFile = Join-Path $benchmarkRoot "docker-compose.yml"
$resultDirectory = Join-Path $benchmarkRoot "results"
$startedAt = Get-Date

Push-Location $benchmarkRoot
try {
    docker compose -f $composeFile up -d --wait
    $servicesReadyAt = Get-Date
    foreach ($recordCount in $RecordCounts) {
        $resultFile = Join-Path $resultDirectory ("benchmark-{0}-records.json" -f $recordCount)
        & $PythonExe "run_persistence_benchmark.py" --record-count $recordCount --repetitions $Repetitions --result-file $resultFile
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed for $recordCount records." }
        $resourceSnapshot = [ordered]@{
            recordCount = $recordCount
            dockerStats = @(docker stats --no-stream --format '{{json .}}' anomaly-persistence-benchmark-mongodb-1 anomaly-persistence-benchmark-couchdb-1 anomaly-persistence-benchmark-postgresql-1 | ConvertFrom-Json)
            storageBytes = [ordered]@{
                mongodb = [long](docker exec anomaly-persistence-benchmark-mongodb-1 sh -c 'du -sb /data/db | cut -f1')
                couchdb = [long](docker exec anomaly-persistence-benchmark-couchdb-1 sh -c 'du -sb /opt/couchdb/data | cut -f1')
                postgresql = [long](docker exec anomaly-persistence-benchmark-postgresql-1 sh -c 'du -sb /var/lib/postgresql/data | cut -f1')
            }
        }
        $resourceSnapshot | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $resultDirectory ("resources-{0}-records.json" -f $recordCount))
    }
    $recoveryStartedAt = Get-Date
    docker compose -f $composeFile restart
    docker compose -f $composeFile up -d --wait
    & $PythonExe "verify_recovery.py"
    if ($LASTEXITCODE -ne 0) { throw "Recovery verification failed." }
    $metadata = [ordered]@{
        startedAt = $startedAt.ToUniversalTime().ToString("o")
        startupElapsedMs = [math]::Round(($servicesReadyAt - $startedAt).TotalMilliseconds, 3)
        recordCounts = $RecordCounts
        repetitions = $Repetitions
        recoveryElapsedMs = [math]::Round(((Get-Date) - $recoveryStartedAt).TotalMilliseconds, 3)
    }
    $metadata | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $resultDirectory "run-metadata.json")
    Write-Host "Results: $resultDirectory"
}
finally {
    Pop-Location
}
