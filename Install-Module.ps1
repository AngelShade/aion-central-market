param(
 [Parameter(Mandatory=$true)][ValidateSet('source','server','client')][string]$Kind,
 [Parameter(Mandatory=$true)][string]$Target,
 [Parameter(Mandatory=$true)][string]$OutputRoot,
 [string]$OriginalClient,[string]$ServerUrl='http://127.0.0.1:8091',
 [string]$ServerArchive,[string]$BaselineArchive,[string]$Media,
 [string]$IntegrationSource
)
$ErrorActionPreference='Stop'
$release=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'module-release.json') | ConvertFrom-Json
if(-not $IntegrationSource){
 if($release.commit -notmatch '^[0-9a-f]{40}$'){throw 'Invalid pinned integration release.'}
 $output=[IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
 $targetPath=[IO.Path]::GetFullPath($Target).TrimEnd('\')
 $repoPath=[IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
 foreach($excluded in @($targetPath,$repoPath)){
  if($output -eq $excluded -or $output.StartsWith($excluded+'\',[StringComparison]::OrdinalIgnoreCase) -or ($excluded -eq $targetPath -and $excluded.StartsWith($output+'\',[StringComparison]::OrdinalIgnoreCase))){throw 'Use an external output/recovery folder.'}
 }
 $IntegrationSource=Join-Path $output ('tooling/mods-'+$release.commit.Substring(0,12))
 if(-not (Test-Path -LiteralPath $IntegrationSource)){
  New-Item -ItemType Directory -Force -Path $IntegrationSource | Out-Null
  & git init $IntegrationSource
  if($LASTEXITCODE -ne 0){throw 'Cannot create integration source checkout.'}
  & git -C $IntegrationSource config core.longpaths true
  if($LASTEXITCODE -ne 0){throw 'Cannot enable Git long-path handling for the integration cache.'}
  & git -C $IntegrationSource remote add origin $release.url
  if($LASTEXITCODE -ne 0){throw 'Cannot configure integration source.'}
  & git -C $IntegrationSource fetch --depth 1 origin $release.commit
  if($LASTEXITCODE -ne 0){throw 'Cannot download the pinned integration release.'}
  & git -C $IntegrationSource checkout --detach FETCH_HEAD
  if($LASTEXITCODE -ne 0){throw 'Cannot check out integration release.'}
 }
 $actual=& git -C $IntegrationSource rev-parse HEAD
 if($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $release.commit){throw 'Integration checkout does not match the pinned release.'}
 $dirty=& git -C $IntegrationSource status --porcelain --untracked-files=no
 if($LASTEXITCODE -ne 0 -or $dirty){throw 'Pinned integration source was modified; preserve it and use a separate output folder.'}
}
$forward=@{}
foreach($key in $PSBoundParameters.Keys){if($key -ne 'IntegrationSource'){$forward[$key]=$PSBoundParameters[$key]}}
& (Join-Path $IntegrationSource 'tools/modules/Install.ps1') -Module $release.module @forward
