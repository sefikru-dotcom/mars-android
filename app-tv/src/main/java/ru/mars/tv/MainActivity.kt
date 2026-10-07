package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.mars.network.credential.AndroidKeyStoreCredentialStore

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = if (AndroidKeyStoreCredentialStore(this).read() != null) {
            DashboardActivity::class.java
        } else {
            PairingActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
