# Load environment variables from parent .env file
Get-Content "$PSScriptRoot\..\\.env" | ForEach-Object {
    if ($_ -match '^([^#][^=]+)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2].Trim()
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

# Run the scheduler
java -jar "$PSScriptRoot\target\staff-scheduler-1.0-SNAPSHOT.jar" $args
