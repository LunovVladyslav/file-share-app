import { spawn } from 'node:child_process';

/**
 * Native file/folder pickers.
 *
 * A browser <input type="file"> hands back file *contents* but never paths, so
 * picking through the OS dialog is what lets the sender read straight from disk
 * — no copy through the UI, and folders keep their structure.
 */
export async function pickFiles() {
  return runPicker('files');
}

export async function pickFolder() {
  return runPicker('folder');
}

function runPicker(mode) {
  switch (process.platform) {
    case 'win32': return windowsPicker(mode);
    case 'darwin': return macPicker(mode);
    default: return linuxPicker(mode);
  }
}

const PS_FILES = `
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Multiselect = $true
$dialog.Title = 'FlyShare — select files to send'
if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  $dialog.FileNames | ForEach-Object { Write-Output $_ }
}`;

const PS_FOLDER = `
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = 'FlyShare — select a folder to send'
if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  Write-Output $dialog.SelectedPath
}`;

function windowsPicker(mode) {
  // -STA is required: the WinForms dialogs will not open on an MTA thread.
  return exec('powershell.exe', [
    '-STA', '-NoProfile', '-NonInteractive', '-Command',
    mode === 'folder' ? PS_FOLDER : PS_FILES,
  ]);
}

const OSA_FILES = `
set chosen to choose file with prompt "FlyShare — select files to send" with multiple selections allowed
set out to ""
repeat with item_ref in chosen
  set out to out & POSIX path of item_ref & linefeed
end repeat
return out`;

const OSA_FOLDER = `
set chosen to choose folder with prompt "FlyShare — select a folder to send"
return POSIX path of chosen`;

function macPicker(mode) {
  const script = mode === 'folder' ? OSA_FOLDER : OSA_FILES;
  const args = [];
  for (const line of script.trim().split('\n')) args.push('-e', line);
  return exec('osascript', args);
}

async function linuxPicker(mode) {
  const zenity = mode === 'folder'
    ? ['--file-selection', '--directory']
    : ['--file-selection', '--multiple', '--separator=\n'];
  try {
    return await exec('zenity', [...zenity, '--title=FlyShare']);
  } catch {
    const kdialog = mode === 'folder' ? ['--getexistingdirectory', '.'] : ['--getopenfilename', '.'];
    return exec('kdialog', kdialog);
  }
}

function exec(command, args) {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    let child;
    try {
      child = spawn(command, args, { windowsHide: true });
    } catch (err) {
      return reject(new Error(`cannot open the file dialog (${command}): ${err.message}`));
    }
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (err) => reject(new Error(`cannot open the file dialog (${command}): ${err.message}`)));
    child.on('close', (code) => {
      // A cancelled dialog is a normal outcome, not an error: zenity and
      // osascript both exit non-zero for it, so treat empty output as "cancelled".
      const paths = stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
      if (paths.length > 0) return resolve(paths);
      if (code === 0) return resolve([]);
      if (/cancel/i.test(stderr) || code === 1) return resolve([]);
      reject(new Error(stderr.trim() || `file dialog exited with code ${code}`));
    });
  });
}
