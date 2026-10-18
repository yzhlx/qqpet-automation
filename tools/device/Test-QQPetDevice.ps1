param(
    [string]$Device = 'DKS9K23420020242',
    [switch]$Recover,
    [switch]$CreateDisplay
)

<#
.SYNOPSIS
    Device precheck + Shizuku recovery for QQ pet hosting real-device runs.

.DESCRIPTION
    Fixed check order: device connection -> Shizuku Binder -> app permission ->
    UserService. Recovery starts Shizuku at most ONCE and then verifies the
    Binder through the app-side path (the app's own status screen), never
    through a shell return code alone.

    Scope: this repository (VirtualDisplay) only. No cross-project access.

    Verified start method (2026-10-14, Shizuku 13.5 / 13.6, Huawei FOA-AL00 /
    Android 12, no root): the legacy start.sh path does not exist in this
    version. Locate libshizuku.so under the installed APK's lib directory at
    runtime and execute it directly.

    Explicitly NOT used:
      - start.sh from older Shizuku versions
      - a "sh" prefix in front of libshizuku.so
      - a hardcoded APK install directory
      - pkill -f shizuku_server
      - force-stop / uninstall / clear-data of Shizuku
      - system security configuration changes
      - "service check shizuku" as the sole liveness signal (SELinux hides it
        from the shell domain even when the service is alive and healthy)

    Note on "service check shizuku": the shell domain (uid 2000) is denied
    { find } on service_manager for name=shizuku, so this command reports
    "not found" while shizuku_server is running perfectly. It is recorded here
    as evidence, never as the gate.
#>

$ErrorActionPreference = 'Stop'
# This script lives at <workspace>/VirtualDisplay/tools/device/, while the adb
# toolchain lives at <workspace>/tools/android-build/sdk/platform-tools (the
# same convention run-mvp-gates.ps1 uses). Resolve upward from the script
# location, never from the caller's working directory.
$taskWorkspace = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$taskAdbCandidates = @(
    (Join-Path $taskWorkspace 'tools\android-build\sdk\platform-tools\adb.exe'),
    (Join-Path $PSScriptRoot '..\..\..\tools\android-build\sdk\platform-tools\adb.exe')
)
$taskAdb = $taskAdbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$taskRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskShizukuPkg = 'moe.shizuku.privileged.api'
$taskPetPkg = 'com.ynk.virtualdisplay'
$taskEvidenceDir = Join-Path $taskRepo 'deliverables\device-precheck'
$taskProfile = @{ Width = 720; Height = 1600; Dpi = 320 }

if (!(Test-Path -LiteralPath $taskAdb)) { throw "adb not found at $taskAdb" }
New-Item -ItemType Directory -Path $taskEvidenceDir -Force | Out-Null

$taskLog = [System.Collections.Generic.List[string]]::new()
function Write-Step {
    param([string]$Name, [string]$Status, [string]$Detail = '')
    $line = "{0,-28} {1,-9} {2}" -f $Name, $Status, $Detail
    Write-Host $line
    $taskLog.Add($line) | Out-Null
}

function Invoke-Adb {
    param([string[]]$Arguments)
    # Windows PowerShell 5.1 converts native stderr into a *terminating* error
    # under $ErrorActionPreference='Stop', and neither 2>&1 nor 2>file avoids
    # that. Relax the preference for the duration of this call only. adb prints
    # harmless warnings such as "Activity not started" to stderr; they must not
    # abort a device precheck.
    $taskStrict = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $taskAdb -s $Device @Arguments 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $taskStrict
    }
}

# ── 1. device connection ──────────────────────────────────────────────────────
$taskState = (Invoke-Adb @('get-state')).Trim()
if ($LASTEXITCODE -ne 0 -or $taskState -ne 'device') {
    Write-Step 'device-connection' 'BLOCKED' "state='$taskState'"
    Write-Host 'STOP: target device is not in the "device" state. No recovery attempted.'
    exit 2
}
Write-Step 'device-connection' 'PASS' $Device

# ── 2. Shizuku Manager installed ─────────────────────────────────────────────
$taskPathOut = Invoke-Adb @('shell', "pm path $taskShizukuPkg")
$taskApk = ($taskPathOut | Where-Object { $_ -match '/base\.apk\s*$' } | Select-Object -First 1) -replace '^package:', ''
$taskApk = if ($null -ne $taskApk) { $taskApk.Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($taskApk)) {
    Write-Step 'shizuku-manager' 'BLOCKED' 'package not installed'
    Write-Host 'STOP: Shizuku Manager APK not found. This requires manual installation.'
    exit 2
}
Write-Step 'shizuku-manager' 'PASS' $taskApk

# ── UI helpers ───────────────────────────────────────────────────────────────
# Compose renders bottom-nav TextViews with bounds [0,0][0,0], so their own
# bounds are useless. The enclosing clickable View does carry real bounds, so
# resolve a label to the *nearest ancestor* with a non-degenerate box instead
# of assuming fixed coordinates. Display 0 is 1084x2412 on the target device,
# and those coordinates would be wrong on any other hardware.
$taskDumpPath = '/sdcard/qqpet-precheck.xml'

function Get-UiDump {
    # uiautomator emits the whole hierarchy as ONE line. Split it into one entry
    # per <node .../> so every Where-Object / -match filter downstream sees a
    # single node instead of the entire document.
    $taskRaw = Invoke-Adb @('shell', "uiautomator dump $taskDumpPath; cat $taskDumpPath; rm -f $taskDumpPath")
    $taskText = ($taskRaw -join "`n")
    return @($taskText -split '<node ' | Where-Object { $_ -match 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' })
}

function Split-UiNodes {
    param([string[]]$Dump)
    <#
      Normalise a dump into one entry per node.

      Get-UiDump ALREADY returns a split array, so this must not split again:
      re-joining those entries loses the '<node ' separators and the second split
      collapses everything into a single element, which silently makes every
      label lookup fail. Only handle the still-joined single-line form.
    #>
    if ($null -eq $Dump) { return @() }
    if ($Dump.Count -gt 1) { return @($Dump | Where-Object { $_ -match 'bounds="' }) }
    $taskText = [string]($Dump | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($taskText)) { return @() }
    return @($taskText -split '<node ' | Where-Object { $_ -match 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' })
}

function Get-BoundsOf {
    param([string]$Text, [string[]]$Dump)
    <#
      Resolve a UI label to a tappable centre.

      Only the text="..." attribute identifies a label. Matching the raw node
      substring would also hit resource-id, content-desc or class names, and a
      stray match there produced a completely wrong centre once already.

      Compose can publish the same text on both the leaf TextView and an ancestor
      that merges its semantics. Prefer the SMALLEST non-degenerate box: the leaf
      that actually draws the glyphs. An ancestor centre can land outside the
      clickable row when the row is partly scrolled, which silently taps nothing.
    #>
    $taskNodes = Split-UiNodes -Dump $Dump
    $taskLabelPattern = 'text="' + [regex]::Escape($Text) + '"'
    $taskBest = $null
    $taskBestArea = 0
    foreach ($taskNode in $taskNodes) {
        if ($taskNode -notmatch $taskLabelPattern) { continue }
        $taskBounds = [regex]::Match($taskNode, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $taskBounds.Success) { continue }
        $taskL = [int]$taskBounds.Groups[1].Value
        $taskT = [int]$taskBounds.Groups[2].Value
        $taskR = [int]$taskBounds.Groups[3].Value
        $taskB = [int]$taskBounds.Groups[4].Value
        if ($taskR -le $taskL -or $taskB -le $taskT) { continue }
        $taskArea = ($taskR - $taskL) * ($taskB - $taskT)
        if ($null -eq $taskBest -or $taskArea -lt $taskBestArea) {
            $taskBestArea = $taskArea
            $taskBest = [pscustomobject]@{
                Text  = $Text
                Left  = $taskL
                Top   = $taskT
                Right = $taskR
                Bottom = $taskB
                X = [int](($taskL + $taskR) / 2)
                Y = [int](($taskT + $taskB) / 2)
            }
        }
    }
    if ($null -ne $taskBest) { return $taskBest }
    # Degenerate label box: the bottom-nav labels are drawn with [0,0][0,0], so
    # derive the tab centre from the clickable rows of the tab bar.
    return (Get-BottomTabCentre -Text $Text -Dump $Dump)
}

function Get-ScreenSize {
    $taskOut = Invoke-Adb @('shell', 'wm size')
    $taskMatch = [regex]::Match(($taskOut -join "`n"), '(\d+)x(\d+)')
    if (-not $taskMatch.Success) { return $null }
    return [pscustomobject]@{ Width = [int]$taskMatch.Groups[1].Value; Height = [int]$taskMatch.Groups[2].Value }
}

function Get-BottomTabCentre {
    param([string]$Text, [string[]]$Dump)
    <#
      Locate a bottom-nav tab by reading the tab bar from the live UI tree.

      Compose renders the tab *labels* with degenerate bounds, but the enclosing
      clickable rows do carry real boxes and appear in left-to-right tab order:
      托管 / 记录 / 设置.

      The bottom band also contains page content that happens to be clickable, so
      filter to the LAST row band: the three tabs are the only clickable boxes
      that share the lowest top coordinate and are roughly square and small.
      Equal-thirds arithmetic is wrong here because the bar has horizontal
      padding that makes the tabs unequal.
    #>
    $taskOrder = @{ '托管' = 0; '记录' = 1; '设置' = 2 }
    if (-not $taskOrder.ContainsKey($Text)) { return $null }
    if ($null -eq $Dump) { return $null }
    $taskRows = @()
    foreach ($taskNode in (Split-UiNodes -Dump $Dump)) {
        if ($taskNode -notmatch 'clickable="true"') { continue }
        $taskBounds = [regex]::Match($taskNode, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $taskBounds.Success) { continue }
        $taskL = [int]$taskBounds.Groups[1].Value
        $taskT = [int]$taskBounds.Groups[2].Value
        $taskR = [int]$taskBounds.Groups[3].Value
        $taskB = [int]$taskBounds.Groups[4].Value
        if ($taskR -le $taskL -or $taskB -le $taskT) { continue }
        $taskH = $taskB - $taskT
        $taskW = $taskR - $taskL
        # The tab cells are short (about 70px tall) and narrow (about 170px).
        # Page containers in the same band are far wider and taller.
        if ($taskH -gt 140 -or $taskW -gt 320) { continue }
        $taskRows += [pscustomobject]@{
            L = $taskL; T = $taskT; R = $taskR; B = $taskB
            X = [int](($taskL + $taskR) / 2); Y = [int](($taskT + $taskB) / 2)
        }
    }
    if ($taskRows.Count -lt 3) { return $null }
    $taskMaxTop = ($taskRows | Measure-Object -Property T -Maximum).Maximum
    $taskBarRows = @($taskRows | Where-Object { $_.T -ge ($taskMaxTop - 40) })
    if ($taskBarRows.Count -lt 3) { return $null }
    $taskSorted = @($taskBarRows | Sort-Object -Property X)
    $taskIndex = $taskOrder[$Text]
    if ($taskIndex -ge $taskSorted.Count) { return $null }
    return $taskSorted[$taskIndex]
}


function Invoke-TapLabel {
    param([string]$Text, [int]$Retries = 6)
    <#
      Tap a UI label, scrolling when it is below the fold.

      Tap the LABEL's own centre. A settings row renders as a text label inside a
      wider clickable parent whose own text is empty, so "the clickable node that
      carries the text" does not exist for those rows; the label centre is inside
      the parent's box and therefore tappable, and it is unambiguous to locate.
    #>
    for ($taskAttempt = 1; $taskAttempt -le $Retries; $taskAttempt++) {
        $taskDump = Get-UiDump
        $taskLabelHits = @($taskDump | Where-Object { $_ -match 'text="' + [regex]::Escape($Text) + '"' })
        $taskHit = Get-BoundsOf -Text $Text -Dump $taskDump
        if ($null -ne $taskHit) {
            Invoke-Adb @('shell', "input tap $($taskHit.X) $($taskHit.Y)") | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
        # The label may be below the fold on a scrollable page.
        Invoke-Adb @('shell', 'input swipe 540 1700 540 900 300') | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

function Show-PetApp {
    <#
      Bring the pet app to the main tab bar.

      Two traps here:
      1. Re-issuing the launch intent only brings the existing Activity forward;
         it does NOT unwind an in-app overlay such as the display console.
      2. KEYCODE_BACK pops the whole task and surfaces whatever sits beneath the
         pet app in the task stack (Shizuku on the target device), which then
         hides the pet app entirely.

      So: launch, then click the console's own top-left back affordance until no
      overlay remains, and finally re-launch to guarantee the pet app is in
      front. "No overlay" is detected by the presence of a bottom tab bar.
    #>
    Invoke-Adb @('shell', "am start --display 0 -n $taskPetPkg/.ui.main.MainActivity") | Out-Null
    Start-Sleep -Seconds 3
    for ($taskPass = 1; $taskPass -le 3; $taskPass++) {
        $taskDump = Get-UiDump
        $taskHasTabBar = ($taskDump | Where-Object { $_ -match 'text="托管"' }).Count -gt 0
        if ($taskHasTabBar) { break }
        $taskBack = $null
        foreach ($taskNode in $taskDump) {
            if ($taskNode -notmatch 'clickable="true"') { continue }
            $taskBounds = [regex]::Match($taskNode, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $taskBounds.Success) { continue }
            $taskL = [int]$taskBounds.Groups[1].Value
            $taskT = [int]$taskBounds.Groups[2].Value
            $taskR = [int]$taskBounds.Groups[3].Value
            $taskB = [int]$taskBounds.Groups[4].Value
            if ($taskR -le $taskL -or $taskB -le $taskT) { continue }
            if ($taskT -lt 260 -and $taskL -lt 260) { $taskBack = $taskBounds; break }
        }
        if ($null -eq $taskBack) { break }   # nothing left to unwind
        $taskTapX = (([int]$taskBack.Groups[1].Value + [int]$taskBack.Groups[3].Value) / 2)
        $taskTapY = (([int]$taskBack.Groups[2].Value + [int]$taskBack.Groups[4].Value) / 2)
        Invoke-Adb @('shell', "input tap $taskTapX $taskTapY") | Out-Null
        Start-Sleep -Seconds 3
    }
    Invoke-Adb @('shell', "am start --display 0 -n $taskPetPkg/.ui.main.MainActivity") | Out-Null
    Start-Sleep -Seconds 3
}

# ── 3. Shizuku Binder ────────────────────────────────────────────────────────
# Shell-domain liveness is recorded as evidence but is NOT the gate, because
# SELinux hides the service from the shell domain.
$taskServiceCheck = (Invoke-Adb @('shell', 'service check shizuku')).Trim()
$taskServerProc = Invoke-Adb @('shell', 'ps -A') | Where-Object { $_ -match 'shizuku_server' }
$taskServerAlive = [bool]$taskServerProc

# The real Binder gate is the app-side path: does the pet app report the
# Shizuku permission as granted? That is what PetHomeScreen.kt requires before
# it enables 开始托管.
function Test-OnSettingsTab {
    <#
      True when the app is already showing the 设置 tab.

      The 设置 screen carries its own title in the top bar (small box near the
      top-left), while the bottom-nav tab of the same name sits in the bottom
      band. Distinguish them by position. Do not require a scrolled-in section
      heading: the settings page is long, so 权限与环境 is not always on screen.
      Mistaking the current tab makes the caller tap 设置 again, which bounces
      the app back to 托管 and hides the rows we need to read.
    #>
    $taskDump = Get-UiDump
    foreach ($taskNode in $taskDump) {
        if ($taskNode -notmatch 'text="' + [char]0x8BBE + [char]0x7F6E + '"') { continue }
        $taskBounds = [regex]::Match($taskNode, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $taskBounds.Success) { continue }
        $taskT = [int]$taskBounds.Groups[2].Value
        if ($taskT -lt 400) { return $true }   # title bar, not the bottom tab
    }
    return $false
}

function Show-SettingsTop {
    <#
      Make sure the 设置 tab is visible and scrolled to its top.

      The settings page is a long scrollable column. Every entry point that reads
      a row from it (the Shizuku permission row, the console link) needs the page
      at the top, otherwise the row is simply absent from the accessibility tree.
    #>
    if (-not (Test-OnSettingsTab)) {
        Invoke-TapLabel -Text '设置' | Out-Null
        Start-Sleep -Seconds 2
    }
    # Several upward swipes: the page may have been left scrolled deep.
    1..4 | ForEach-Object {
        Invoke-Adb @('shell', 'input swipe 540 700 540 1900 250') | Out-Null
        Start-Sleep -Seconds 1
    }
    Start-Sleep -Seconds 2
}

function Test-PetShizukuGranted {
    # The permission row lives on the 设置 tab. Show-PetApp can already leave us
    # The permission row lives on the 设置 tab. Show-PetApp can already leave us
    # there (BACK unwinds console -> settings), so check the current screen
    # before tapping the tab again: tapping 设置 while already on 设置 would
    # bounce back to 托管 and hide the row this function must read.
    Show-PetApp
    Start-Sleep -Seconds 2
    Show-SettingsTop
    $taskDump = Get-UiDump
    $taskGranted = ($taskDump | Where-Object { $_ -match 'text="' + [char]0x540E + [char]0x53F0 + [char]0x622A + [char]0x56FE + [char]0x4E0E + [char]0x8F93 + [char]0x5165 + [char]0x5DF2 + [char]0x6388 + [char]0x6743 + '"' }).Count -gt 0
    return $taskGranted
}

$taskGranted = Test-PetShizukuGranted

if ($taskGranted) {
    Write-Step 'shizuku-binder' 'PASS' 'app reports Shizuku permission granted'
    Write-Step 'app-permission' 'PASS' 'granted'
} else {
    if (!$taskServerAlive) {
        Write-Step 'shizuku-binder' 'FAIL' "service-check='$taskServiceCheck'; shizuku_server not running"
        if (-not $Recover) {
            Write-Host 'STOP: Binder unavailable and -Recover not specified. Re-run with -Recover.'
            exit 3
        }
        # ── 4. recovery: at most one start attempt ────────────────────────────
        Write-Host ''
        Write-Host 'Recovery: starting Shizuku once via libshizuku.so ...'
        $taskSoPath = $taskApk -replace '/base\.apk$', '/lib/arm64/libshizuku.so'
        $taskLs = Invoke-Adb @('shell', "ls -l $taskSoPath")
        if ($LASTEXITCODE -ne 0 -or $taskLs -match 'No such file') {
            Write-Step 'shizuku-recovery' 'BLOCKED' "libshizuku.so missing at $taskSoPath"
            Write-Host 'STOP: start file not found. No further attempts.'
            exit 4
        }
        Write-Host $taskLs
        $taskStart = Invoke-Adb @('shell', $taskSoPath)
        if ($LASTEXITCODE -ne 0) {
            Write-Step 'shizuku-recovery' 'FAIL' "start command exited $LASTEXITCODE"
            Write-Host 'STOP: start command failed. No retry.'
            exit 4
        }
        # Verify through the app-side path after a short bounded wait. Do not
        # re-run the start command.
        $taskVerified = $false
        foreach ($taskWaitMs in 2000, 3000, 5000) {
            Start-Sleep -Milliseconds $taskWaitMs
            $taskVerified = Test-PetShizukuGranted
            if ($taskVerified) { break }
        }
        if (-not $taskVerified) {
            Write-Step 'shizuku-recovery' 'FAIL' 'Binder still not granted after one start attempt'
            Write-Host 'STOP: Shizuku started but the app is not authorized. This needs the user to grant permission in the Shizuku Manager UI.'
            exit 5
        }
        Write-Step 'shizuku-recovery' 'PASS' 'one start attempt; app-side verification passed'
        Write-Step 'app-permission' 'PASS' 'granted after recovery'
    } else {
        # Server is alive but the app is not authorized. Starting again will not
        # help; only the user can approve this.
        Write-Step 'shizuku-binder' 'FAIL' 'shizuku_server alive but app not authorized'
        Write-Step 'app-permission' 'BLOCKED' 'not granted'
        Write-Host 'STOP: Shizuku service is running but 宠物托管 is not authorized. Grant it in the Shizuku Manager UI (已授权的应用), then re-run. No automated bypass.'
        exit 6
    }
}

# ── 5. UserService bound (privileged service connected) ─────────────────────
# The console screen reports 特权服务: 已连接, which is the app's own UserService
# binding state. Reach it through 设置 › 虚拟屏控制台.
Show-PetApp
Start-Sleep -Seconds 2
Show-SettingsTop
Invoke-TapLabel -Text '虚拟屏控制台' | Out-Null
Start-Sleep -Seconds 4
$taskConsoleDump = Get-UiDump
$taskPrivConnected = ($taskConsoleDump | Where-Object { $_ -match 'text="' + [char]0x7279 + [char]0x6743 + '服务: ' + [char]0x5DF2 + [char]0x8FDE + [char]0x63A5 + '"' }).Count -gt 0
$taskVdRow = $taskConsoleDump | Where-Object { $_ -match 'text="' + [char]0x663E + [char]0x793A + [char]0x5668 + ' #\d+"' } | Select-Object -First 1
# Match the size label with an ASCII pattern: "<digits> x <digits>". The
# separator is U+00D7 in the app, so build it from a char code rather than
# embedding a non-ASCII literal that Windows PowerShell 5.1 may mis-decode.
$taskVdSize = $taskConsoleDump | Where-Object { $_ -match ('\d+ ' + [char]0x00D7 + ' \d+') } | Select-Object -First 1
# The console's live-state label is 已连接 (bound and streaming). 已就绪 is the
# settings-screen wording for the same condition. Accept either, but require the
# label to come from the active-display card, not from anywhere on the page.
$taskVdLive = ($taskConsoleDump | Where-Object { $_ -match ('text="(' + [char]0x5DF2 + [char]0x8FDE + [char]0x63A5 + '|' + [char]0x5DF2 + [char]0x5C31 + [char]0x7EEA + ')"') }).Count -gt 0

if ($taskPrivConnected) {
    Write-Step 'userservice' 'PASS' '特权服务: 已连接'
} else {
    Write-Step 'userservice' 'FAIL' 'privileged service not reported connected'
}

# ── 6. VirtualDisplay present at profile size ────────────────────────────────
# $taskVdRow / $taskVdSize are raw XML nodes, so extract just their text
# attribute before reporting. Interpolating the raw node would dump the whole
# element (quotes, bounds, flags) into the evidence line.
function Get-NodeText {
    param([string]$Node)
    if ([string]::IsNullOrWhiteSpace($Node)) { return '' }
    $taskMatch = [regex]::Match($Node, 'text="([^"]*)"')
    if ($taskMatch.Success) { return $taskMatch.Groups[1].Value }
    return ''
}
$taskVdDetail = [string]::Join(' / ', @((Get-NodeText $taskVdRow), (Get-NodeText $taskVdSize)))
if ($taskVdRow -and $taskVdLive) {
    Write-Step 'virtualdisplay' 'PASS' $taskVdDetail
} else {
    Write-Step 'virtualdisplay' 'ABSENT' $taskVdDetail
    if ($CreateDisplay) {
        Write-Host ''
        Write-Host 'Creating VirtualDisplay via the app console ...'
        # Resolve every control from the live UI tree. The console's own bounds
        # depend on the physical screen, so hardcoded coordinates would break on
        # any other device.
        Invoke-TapLabel -Text '新建显示器' | Out-Null
        Start-Sleep -Seconds 3
        $taskDialog = Get-UiDump
        # The 手机 720p preset fills 720x1280@320. Height must be corrected to
        # 1600 because PetProfile hard-requires exactly 720x1600@320.
        $taskPreset = Get-BoundsOf -Text '手机 720p' -Dump $taskDialog
        $taskHeight = Get-BoundsOf -Text '高度' -Dump $taskDialog
        $taskCreate = Get-BoundsOf -Text '创建并启动' -Dump $taskDialog
        if ($null -eq $taskPreset -or $null -eq $taskHeight -or $null -eq $taskCreate) {
            Write-Step 'virtualdisplay' 'FAIL' 'new-display dialog controls not found'
        } else {
            Invoke-Adb @('shell', "input tap $($taskPreset.X) $($taskPreset.Y)") | Out-Null
            Start-Sleep -Seconds 2
            Invoke-Adb @('shell', "input tap $($taskHeight.X) $($taskHeight.Y)") | Out-Null
            Start-Sleep -Seconds 2
            # Clear the field, then type the profile height.
            Invoke-Adb @('shell', 'input keyevent 123') | Out-Null     # MOVE_END
            1..8 | ForEach-Object { Invoke-Adb @('shell', 'input keyevent 67') | Out-Null }  # DEL
            Start-Sleep -Seconds 1
            Invoke-Adb @('shell', 'input text 1600') | Out-Null
            Start-Sleep -Seconds 2
            # Confirm the dialog really holds 720 / 1600 / 320 before creating.
            $taskConfirm = Get-UiDump
            $taskFields = @()
            foreach ($taskLabel in @('宽度', '高度', 'DPI')) {
                $taskRow = $taskConfirm | Where-Object { $_ -match [regex]::Escape($taskLabel) } | Select-Object -First 1
                $taskFields += $taskLabel
            }
            Write-Host ('dialog labels present: ' + ($taskFields -join ', '))
            Invoke-Adb @('shell', "input tap $($taskCreate.X) $($taskCreate.Y)") | Out-Null
            Start-Sleep -Seconds 6
            $taskConsoleDump = Get-UiDump
            $taskVdRow = $taskConsoleDump | Where-Object { $_ -match 'text="' + [char]0x663E + [char]0x793A + [char]0x5668 + ' #\d+"' } | Select-Object -First 1
            $taskVdSize = $taskConsoleDump | Where-Object { $_ -match ('\d+ ' + [char]0x00D7 + ' \d+') } | Select-Object -First 1
            $taskVdLive = ($taskConsoleDump | Where-Object { $_ -match 'text="(已连接|已就绪)"' }).Count -gt 0
            if ($taskVdRow -and $taskVdLive) {
                Write-Step 'virtualdisplay' 'PASS' ([string]::Join(' / ', @($taskVdRow, $taskVdSize)))
            } else {
                $taskAfterDetail = 'rows=' + [string]$taskVdRow + ' live=' + [string]$taskVdLive
                Write-Step 'virtualdisplay' 'FAIL' $taskAfterDetail
            }
        }
    }
}

# ── 7. screencap the VD and check the pixel size matches PetProfile ─────────
# Parse the display ids on the device side. dumpsys display emits many lines and
# Windows PowerShell mangles long native output, so let grep do the filtering
# and only transfer the short id list.
$taskDisplayIds = Invoke-Adb @('shell', 'dumpsys display | grep -o "displayId=[0-9]*" | cut -d= -f2 | sort -u') |
    ForEach-Object { ([string]$_).Trim() } |
    Where-Object { $_ -match '^\d+$' } | Sort-Object -Unique
Write-Host ''
Write-Host ('display ids: ' + (($taskDisplayIds | ForEach-Object { [string]$_ }) -join ', '))
$taskCapOk = $false
foreach ($taskId in $taskDisplayIds) {
    if ($taskId -eq '0') { continue }
    # Capture and decode the PNG IHDR on the device so only two integers cross
    # the adb boundary. od + awk avoids any binary-over-shell hazard.
    # Read the 8 IHDR bytes as DECIMAL BYTES and reassemble the two
    # 32-bit big-endian values here. od -tu4 on this device emits a
    # byte-swapped word, so the byte form is the only trustworthy one.
    $taskDim = Invoke-Adb @('shell', "screencap -p -d $taskId /data/local/tmp/qqpet-precheck.png && od -An -tu1 -j16 -N8 /data/local/tmp/qqpet-precheck.png; rm -f /data/local/tmp/qqpet-precheck.png")
    # od prints the 8 bytes on ONE space-separated line, so split each line into
    # fields before filtering. Filtering the whole line against ^\d+$ matches
    # nothing and silently yields zero bytes.
    $taskBytes = @()
    foreach ($taskLine in $taskDim) {
        foreach ($taskField in (([string]$taskLine) -split '\s+')) {
            if ($taskField -match '^\d+$') { $taskBytes += [int]$taskField }
        }
    }
    if ($taskBytes.Count -ge 8) {
        $taskW = ($taskBytes[0] * 16777216) + ($taskBytes[1] * 65536) + ($taskBytes[2] * 256) + $taskBytes[3]
        $taskH = ($taskBytes[4] * 16777216) + ($taskBytes[5] * 65536) + ($taskBytes[6] * 256) + $taskBytes[7]
        # Windows PowerShell 5.1 mis-parses "${taskW}x${taskH}" inside a
        # double-quoted string, so build the label explicitly.
        $taskSizeLabel = "$taskW" + 'x' + "$taskH"
        Write-Step 'screencap' 'PASS' "display $taskId -> $taskSizeLabel"
        if ($taskW -eq $taskProfile.Width -and $taskH -eq $taskProfile.Height) {
            $taskCapOk = $true
        }
    }
}
if (-not $taskCapOk) {
    $taskProfileLabel = "$($taskProfile.Width)" + 'x' + "$($taskProfile.Height)"
    Write-Step 'screencap' 'FAIL' "no non-default display produced a $taskProfileLabel frame"
}

# ── evidence ─────────────────────────────────────────────────────────────────
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskEvidenceFile = Join-Path $taskEvidenceDir "precheck-$taskStamp.txt"
$taskLog | Set-Content -LiteralPath $taskEvidenceFile -Encoding utf8
Write-Host ''
Write-Host "evidence: $taskEvidenceFile"

$taskBlocked = $taskLog | Where-Object { $_ -match '\s(BLOCKED|FAIL)\s' }
if ($taskBlocked) {
    Write-Host ''
    Write-Host 'RESULT: BLOCKED'
    exit 1
}
Write-Host 'RESULT: PASS'
