package de.stu.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val adding = intent.getBooleanExtra("add", false)
        if (store.loggedIn && !adding) { openMain(); return }

        setContentView(R.layout.activity_login)
        val name = findViewById<EditText>(R.id.accname)
        val server = findViewById<EditText>(R.id.server)
        val user = findViewById<EditText>(R.id.user)
        val pass = findViewById<EditText>(R.id.pass)
        val btn = findViewById<Button>(R.id.login)
        val progress = findViewById<ProgressBar>(R.id.progress)
        if (adding) btn.text = "Konto hinzufügen"

        btn.setOnClickListener {
            val s = server.text.toString().trim()
            val u = user.text.toString().trim()
            val p = pass.text.toString()
            if (s.isEmpty() || u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Bitte Server, Benutzer und Passwort ausfüllen.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    try { Xtream(s, u, p, store.userAgent).login() } catch (e: Exception) {
                        "Server nicht erreichbar: ${e.message}"
                    }
                }
                progress.visibility = View.GONE
                btn.isEnabled = true
                if (err == null) {
                    val n = name.text.toString().trim().ifEmpty { u }
                    store.addAccount(n, Xtream.normalize(s), u, p)
                    Cache.clear()
                    openMain()
                } else {
                    Toast.makeText(this@LoginActivity, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
