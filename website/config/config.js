// Public client configuration. The anon key is designed to be public (protected by RLS).
// NEVER put the service_role key here. The deploy workflow regenerates this file from GitHub secrets if set.
window.DRIVELINK_CONFIG = {
  supabaseUrl: "https://zfvbaicrqejktfiszefr.supabase.co",
  supabaseAnonKey: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpmdmJhaWNycWVqa3RmaXN6ZWZyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5NjM5NjgsImV4cCI6MjEwOTUzOTk2OH0.vOGLqCB2w8EmTb90BrXIWtFV9rGusYF56X8z3mjmD9E",
  apkUrl: "", // optional: direct link to a hosted APK (e.g. a GitHub release asset)
  googleMapsApiKey: "" // optional: enables the Google map in the simulator
};
