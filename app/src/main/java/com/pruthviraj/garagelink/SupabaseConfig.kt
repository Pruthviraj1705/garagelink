package com.pruthviraj.garagelink

/**
 * Single place for the Supabase project URL and the public "anon" key.
 *
 * The anon key is meant to be public (it ships inside every app build). Data is protected by
 * Row Level Security and server-side checks, not by hiding this key. NEVER put the
 * service_role key or any other secret in this file.
 */
object SupabaseConfig {
    const val URL = "https://eceuvkvairdbhddyebmw.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVjZXV2a3ZhaXJkYmhkZHllYm13Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODU2OTA4MDEsImV4cCI6MjEwMTI2NjgwMX0.WYNjKG4pS4AJaNV9d1oOrYrqD3Fv708jzypH5-hdtj4"
}
