# Save Telegram application credentials privately. Never print them or commit them.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$form = New-Object Windows.Forms.Form
$form.Text = 'Nyanime - Configurazione Telegram'
$form.ClientSize = New-Object Drawing.Size(520, 285)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.Font = New-Object Drawing.Font('Segoe UI', 10)
$description = New-Object Windows.Forms.Label
$description.Text = "Crea la tua applicazione Android su my.telegram.org.`r`nInserisci qui api_id e api_hash. Restano fuori da Git."
$description.SetBounds(24, 20, 470, 50)
$idLabel = New-Object Windows.Forms.Label
$idLabel.Text = 'API ID'
$idLabel.SetBounds(24, 85, 100, 25)
$idBox = New-Object Windows.Forms.TextBox
$idBox.SetBounds(130, 82, 365, 30)
$hashLabel = New-Object Windows.Forms.Label
$hashLabel.Text = 'API hash'
$hashLabel.SetBounds(24, 133, 100, 25)
$hashBox = New-Object Windows.Forms.TextBox
$hashBox.SetBounds(130, 130, 365, 30)
$hashBox.UseSystemPasswordChar = $true
$save = New-Object Windows.Forms.Button
$save.Text = 'Salva configurazione'
$save.SetBounds(275, 200, 220, 40)
$save.Add_Click({
    $taskApiNumber = 0
    if (-not [int]::TryParse($idBox.Text.Trim(), [ref]$taskApiNumber) -or $taskApiNumber -le 0 -or
        $hashBox.Text.Trim() -notmatch '^[a-fA-F0-9]{32}$') {
        [Windows.Forms.MessageBox]::Show('Controlla API ID e API hash.', 'Nyanime') | Out-Null
        return
    }
    try {
        $privateFolder = Join-Path $taskRoot '.local'
        New-Item -ItemType Directory -Force -Path $privateFolder | Out-Null
        $target = Join-Path $privateFolder 'telegram.properties'
        [IO.File]::WriteAllText($target, "api_id=$taskApiNumber`napi_hash=$($hashBox.Text.Trim())`n", (New-Object Text.UTF8Encoding($false)))
        $acl = Get-Acl -LiteralPath $target
        $acl.SetAccessRuleProtection($true, $false)
        $owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'Allow')))
        Set-Acl -LiteralPath $target -AclObject $acl
        $hashBox.Clear()
        [Windows.Forms.MessageBox]::Show('Configurazione salvata privatamente. Avvisa Codex per continuare con login e prove.', 'Nyanime') | Out-Null
        $form.Close()
    } catch {
        [Windows.Forms.MessageBox]::Show('Salvataggio non riuscito. Nessun valore verra mostrato nei log.', 'Nyanime') | Out-Null
    }
})
$form.Controls.AddRange(@($description, $idLabel, $idBox, $hashLabel, $hashBox, $save))
$form.AcceptButton = $save
[void]$form.ShowDialog()
$form.Dispose()
