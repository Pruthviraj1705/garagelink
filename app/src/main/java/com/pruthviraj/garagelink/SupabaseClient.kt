package com.pruthviraj.garagelink

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClientProvider {
    val client = createSupabaseClient(
        supabaseUrl = "https://eceuvkvairdbhddyebmw.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVjZXV2a3ZhaXJkYmhkZHllYm13Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODU2OTA4MDEsImV4cCI6MjEwMTI2NjgwMX0.WYNjKG4pS4AJaNV9d1oOrYrqD3Fv708jzypH5-hdtj4"
    ) {
        install(Auth)
        install(Postgrest)
    }
}