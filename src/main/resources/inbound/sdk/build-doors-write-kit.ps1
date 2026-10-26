param(
 [Parameter(Mandatory=$true)][ValidateSet('UAT','PROD')][string]$Profile,
 [string]$WalletPath = (Join-Path $HOME 'doors/security/doors-identity.p12')
)
$ErrorActionPreference='Stop'
$releaseRoot=Join-Path $PSScriptRoot 'releases'
$stage=Join-Path $releaseRoot ("doors-write-"+$Profile.ToLower()+"-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
foreach($name in @('WriteClient.java','sample.json','openapi.json','README.txt')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage }
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'docs') -PathType Container){Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'docs') -Destination $stage -Recurse}
$walletText=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'doors-wallet.properties'))
function Set-WalletValue([string]$name,[string]$value) {
 $escaped=$value.Replace('\','\\').Replace("`r",'\r').Replace("`n",'\n').Replace("`t",'\t').Replace(' ','\ ')
 $script:walletText=[regex]::Replace($script:walletText,'(?m)^'+$name+'=.*$', [System.Text.RegularExpressions.MatchEvaluator]{param($match) $name+'='+$escaped})
}
if($Profile -eq 'UAT') {
 if(!(Test-Path -LiteralPath $WalletPath -PathType Leaf)){throw "UAT identity missing: $WalletPath"}
 $securePass=Read-Host 'UAT wallet password' -AsSecureString
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass)
 try {
  $plainPass=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  $cert=[System.Security.Cryptography.X509Certificates.X509Certificate2]::new($WalletPath,$plainPass,[System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
  if(!$cert.HasPrivateKey -or $cert.PublicKey.Oid.Value -ne '1.2.840.113549.1.1.1'){throw 'UAT wallet must contain an RSA private key.'}
  $security=Join-Path $stage 'doors/security';New-Item -ItemType Directory -Path $security -Force | Out-Null
  Copy-Item -LiteralPath $WalletPath -Destination (Join-Path $security 'doors-identity.p12')
  $pem="-----BEGIN CERTIFICATE-----`n"+[Convert]::ToBase64String($cert.RawData,[Base64FormattingOptions]::InsertLineBreaks)+"`n-----END CERTIFICATE-----`n"
  [IO.File]::WriteAllText((Join-Path $security 'client_public.pem'),$pem)
  Set-WalletValue 'DOORS_WALLET_PATH' 'doors/security/doors-identity.p12'
  Set-WalletValue 'DOORS_WALLET_PASS' $plainPass
  Set-WalletValue 'DOORS_WALLET_ALIAS' ''
 } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$plainPass=$null;if($cert){$cert.Dispose()} }
} else {
 Set-WalletValue 'DOORS_WALLET_PATH' '~/doors/security/doors-identity.p12'
 Set-WalletValue 'DOORS_WALLET_PASS' ''
 Set-WalletValue 'DOORS_WALLET_ALIAS' ''
}
# Trust is configured by the recipient; never copy an administrator's local truststore path.
Set-WalletValue 'DOORS_TRUSTSTORE_PATH' ''
Set-WalletValue 'DOORS_TRUSTSTORE_PASS' ''
[IO.File]::WriteAllText((Join-Path $stage 'doors-wallet.properties'),$walletText)
$readme='readme-enduser-'+$Profile.ToLower()+'.txt'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot $readme) -Destination $stage
$zip=$stage+'.zip';Compress-Archive -LiteralPath (Get-ChildItem -LiteralPath $stage).FullName -DestinationPath $zip
$hash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
$hash|Set-Content -LiteralPath ($zip+'.sha256') -Encoding Ascii
Copy-Item -LiteralPath (Join-Path $stage $readme) -Destination ($zip+'.readme.txt')
Write-Host "Release ZIP: $zip"
Write-Host "SHA-256 file: $zip.sha256"
Write-Host "End-user README: $zip.readme.txt"
Write-Host 'Before sharing UAT, map doors/security/client_public.pem to the selected Write client and test from an allowed IP. Keep the UAT ZIP private.'
