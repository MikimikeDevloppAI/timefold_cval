$env:SUPABASE_URL = "https://rhrdtrgwfzmuyrhkkulv.supabase.co"
$env:SUPABASE_SERVICE_ROLE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJocmR0cmd3ZnptdXlyaGtrdWx2Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2ODc0MjI2MiwiZXhwIjoyMDg0MzE4MjYyfQ.HotNMGClgwmTqwih3fUZoaEh884lQ5RtlHEUCYWqSGk"
Set-Location "c:\Users\micha\OneDrive\New_db_cval\timefold-solver"
java -jar target\staff-scheduler-1.0-SNAPSHOT.jar $args
