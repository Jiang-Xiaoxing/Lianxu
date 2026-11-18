#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$IdentityDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$InitializeIdentity,
    [switch]$WithDeviceTests
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$private = [IO.Path]::GetFullPath($IdentityDirectory).TrimEnd('\')
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
foreach ($path in @($root, $output)) {
    if (($private+'\').StartsWith($path+'\', 'OrdinalIgnoreCase') -or ($path+'\').StartsWith($private+'\', 'OrdinalIgnoreCase')) {
        throw '签名目录必须独立于源码与产物目录'
    }
}
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$sdk = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$signer = Join-Path $sdk.FullName 'lib/apksigner.jar'
$keystore = Join-Path $private 'testing.p12'
$sealed = Join-Path $private 'password.dpapi'
$counter = Join-Path $private 'version.txt'
if ($InitializeIdentity) { [IO.Directory]::CreateDirectory($private) | Out-Null }
$lock = [IO.File]::Open((Join-Path $private 'build.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
Push-Location $root
try {
    if ($InitializeIdentity) {
        if (@($keystore, $sealed, $counter | Where-Object { Test-Path -LiteralPath $_ }).Count) { throw '已有签名资料，不能重新初始化' }
        $bytes = [byte[]]::new(48)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $secret = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
        [Array]::Clear($bytes)
        [IO.File]::WriteAllText($sealed, (ConvertFrom-SecureString $secret))
        try {
            $env:LIANXU_SIGN_PASS = [Net.NetworkCredential]::new('', $secret).Password
            & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias localtest -keyalg RSA -keysize 3072 -validity 12000 -dname 'CN=Lianxu Local Testing' -storepass:env LIANXU_SIGN_PASS
            if ($LASTEXITCODE) { throw '测试身份初始化失败' }
        } finally { Remove-Item Env:LIANXU_SIGN_PASS -ErrorAction SilentlyContinue }
        [IO.File]::WriteAllText($counter, '0')
    }
    foreach ($file in @($counter,$keystore,$sealed)) { if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw '签名资料不完整，请从原有私密备份恢复' } }
    $code = 1 + [int]([IO.File]::ReadAllText($counter))
    if ($code -lt 1 -or $code -gt 2100000000) { throw '版本号超出范围' }
    & git diff --quiet
    if ($LASTEXITCODE) { throw '请先暂存本次源码，以便记录对应 Git 树' }
    $tree = & git write-tree
    if ($LASTEXITCODE) { throw '无法记录源码树' }
    $untracked = & git ls-files --others --exclude-standard -- app tools gradle
    if ($untracked) { throw '构建输入中有未跟踪的文件，请先核对源码树' }
    $tasks = @(':app:assembleLocalTest')
    if ($WithDeviceTests) { $tasks += ':app:assembleLocalTestAndroidTest' }
    & ./gradlew.bat --offline --max-workers=2 "-PlianxuLocalTestCode=$code" "-PlianxuSourceTree=$($tree.Substring(0,12))" @tasks
    if ($LASTEXITCODE) { throw '测试版构建失败' }
    $folder = Join-Path $output "v$code"
    if (Test-Path -LiteralPath $folder) { throw '同版本产物已经存在，请核对计数器' }
    # Reserve before publishing any signed output. A failed signing may leave a gap, never a reused code.
    [IO.File]::WriteAllText("$counter.new", "$code")
    [IO.File]::Move("$counter.new",$counter,$true)
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $packages = @(@{source='localTest'; name='Lianxu-test'; id='app.lianxu.independent.testing'})
    if ($WithDeviceTests) { $packages += @{source='androidTest/localTest'; name='instrumentation'; id='app.lianxu.independent.testing.test'} }
    $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($sealed))
    try {
        $env:LIANXU_SIGN_PASS = [Net.NetworkCredential]::new('', $secure).Password
        foreach ($pkg in $packages) {
            $dir = Join-Path 'app/build/outputs/apk' $pkg.source
            $meta = Get-Content -LiteralPath (Join-Path $dir 'output-metadata.json') -Raw | ConvertFrom-Json
            if ($meta.applicationId -ne $pkg.id -or $meta.elements.Count -ne 1) { throw 'APK 身份不符' }
            $element = $meta.elements[0]
            if ($pkg.name -eq 'Lianxu-test' -and $element.versionCode -ne $code) { throw 'APK 版本号不符' }
            $apk = Join-Path $folder "$($pkg.name)-$code.apk"
            & $java -jar $signer sign --ks $keystore --ks-key-alias localtest --ks-pass env:LIANXU_SIGN_PASS --v4-signing-enabled false --out $apk (Join-Path $dir $element.outputFile)
            if ($LASTEXITCODE) { throw 'APK 签名失败' }
            & $java -jar $signer verify --print-certs $apk
            if ($LASTEXITCODE) { throw 'APK 签名验证失败' }
        }
    } finally { Remove-Item Env:LIANXU_SIGN_PASS -ErrorAction SilentlyContinue; $secure.Dispose() }
    [IO.File]::WriteAllText((Join-Path $folder 'source-tree.txt'), $tree)
} finally { Pop-Location; $lock.Dispose() }
