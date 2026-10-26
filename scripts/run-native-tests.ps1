$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))
& cmake -S native-tests -B native-tests/build
if ($LASTEXITCODE -ne 0) { throw 'CMake configuration failed' }
& cmake --build native-tests/build --config Release
if ($LASTEXITCODE -ne 0) { throw 'CMake build failed' }
& ctest --test-dir native-tests/build -C Release --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Native tests failed' }
