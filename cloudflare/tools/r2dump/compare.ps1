$snap="C:\Users\Administrator\Till-Recorder\backups\20261010-pre-accessibility\r2"
$now=curl.exe -s "http://127.0.0.1:8799/?p=" | ConvertFrom-Json
$pre='control','enroll','pin-push','removed','status','users','user-sub'
$keys=$now | ? { $pre -contains $_.key.Split('/')[0] }
$snapFiles=Get-ChildItem $snap | ? {$_.Name -match '^(control|enroll__|pin-push|removed|status|users|user-sub)'}
"snapshot files: $($snapFiles.Count); current objects in those prefixes: $($keys.Count)"
foreach($o in $keys){ $f="$snap\"+($o.key -replace '/','__'); $cur=curl.exe -s "http://127.0.0.1:8799/?k=$([uri]::EscapeDataString($o.key))"
 if(-not (Test-Path $f)){ "NEW KEY $($o.key)"; continue }
 $old=[IO.File]::ReadAllText($f)
 if($o.key -like 'status/*'){ $ja=$old|ConvertFrom-Json; $jb=$cur|ConvertFrom-Json; foreach($p in 'lastSeen','recording'){ $ja.PSObject.Properties.Remove($p); $jb.PSObject.Properties.Remove($p) }; $res= if(($ja|ConvertTo-Json -Compress) -eq ($jb|ConvertTo-Json -Compress)){'identical (ignoring lastSeen/recording)'}else{'DIFF now='+$cur}; "$($o.key): $res" }
 else { "$($o.key): " + $(if($old -eq $cur){'identical'}else{'DIFF'}) } }
foreach($sf in $snapFiles){ $k=($sf.Name -replace '__','/'); if(-not ($keys | ? { $_.key -eq $k })){ "MISSING NOW: $k" } }
