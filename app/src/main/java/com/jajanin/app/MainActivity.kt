package com.jajanin.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var ui: JajaninView
    private val authScope = CoroutineScope(Dispatchers.Main)
    private var authForm: AuthForm? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        ui = JajaninView(this)
        setContentView(ui)
        handleAuthLink(intent)
        restoreSessionIfNeeded()
    }

    /**
     * Memulihkan session dari penyimpanan sebelum menentukan layar awal.
     *
     * Harus memakai [SupabaseRepository.awaitLoggedIn]: saat aplikasi baru dibuka
     * status Auth masih LoadingFromStorage, sehingga pengecekan langsung bisa
     * salah menganggap user belum login.
     */
    private fun restoreSessionIfNeeded() {
        authScope.launch {
            if (SupabaseRepository.awaitLoggedIn()) ui.showHome()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
    }

    private fun handleAuthLink(authIntent: Intent?) {
        if (authIntent?.data?.scheme != "jajanin") return
        authScope.launch {
            if (SupabaseRepository.hasSession()) {
                ui.showHome()
                Toast.makeText(this@MainActivity, "Berhasil masuk", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this@MainActivity, "Tautan diterima. Coba masuk kembali.", Toast.LENGTH_LONG).show()
        }
    }

    fun showAuthForm(focusPassword: Boolean, signup: Boolean = false) {
        val open = authForm
        if (open != null && open.isShowing) {
            open.setMode(signup)
            open.focus(focusPassword)
            return
        }
        val form = AuthForm()
        authForm = form
        form.show(signup, focusPassword)
    }

    fun logout() {
        authScope.launch {
            try {
                SupabaseRepository.logout()
                ui.showLogin()
                Toast.makeText(this@MainActivity, "Berhasil keluar", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (!ui.goBack()) super.onBackPressed()
    }

    private inner class AuthForm {
        private val dialog = Dialog(this@MainActivity).apply {
            setContentView(R.layout.dialog_auth)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setOnDismissListener { authForm = null }
        }
        private val title = dialog.findViewById<TextView>(R.id.authTitle)
        private val subtitle = dialog.findViewById<TextView>(R.id.authSubtitle)
        private val email = dialog.findViewById<EditText>(R.id.authEmail)
        private val password = dialog.findViewById<EditText>(R.id.authPassword)
        private val error = dialog.findViewById<TextView>(R.id.authError)
        private val action = dialog.findViewById<Button>(R.id.authAction)
        private val toggle = dialog.findViewById<TextView>(R.id.authToggle)

        private var signup = false
        private var busy = false

        val isShowing: Boolean
            get() = dialog.isShowing

        init {
            email.imeOptions = EditorInfo.IME_ACTION_NEXT
            password.imeOptions = EditorInfo.IME_ACTION_GO
            password.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
            action.setOnClickListener { submit() }
            toggle.setOnClickListener { setMode(!signup) }
        }

        fun show(signup: Boolean, focusPasswordField: Boolean) {
            this.signup = signup
            applyMode()
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            focus(focusPasswordField)
        }

        fun setMode(signup: Boolean) {
            this.signup = signup
            applyMode()
            error.visibility = View.GONE
        }

        fun focus(focusPasswordField: Boolean) {
            val target = if (focusPasswordField) password else email
            target.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            target.post { imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }
        }

        private fun applyMode() {
            title.text = if (signup) "Buat akun" else "Masuk dengan email"
            subtitle.text = if (signup) "Daftar untuk mulai jajan di kampus." else "Lanjutkan jajan favoritmu di kampus."
            action.text = if (signup) "Daftar" else "Masuk"
            toggle.text = if (signup) "Sudah punya akun?  Masuk" else "Belum punya akun?  Daftar"
        }

        private fun fail(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
        }

        private fun setBusy(value: Boolean) {
            busy = value
            action.isEnabled = !value
            toggle.isEnabled = !value
            action.text = when {
                value && signup -> "Mendaftar..."
                value -> "Masuk..."
                signup -> "Daftar"
                else -> "Masuk"
            }
        }

        private fun submit() {
            if (busy) return
            error.visibility = View.GONE
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailValue).matches() || passwordValue.length < 6) {
                fail("Masukkan email valid dan kata sandi minimal 6 karakter")
                return
            }
            if (!SupabaseRepository.isConfigured()) {
                fail("Supabase belum dikonfigurasi. Tambahkan SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY di ~/.gradle/gradle.properties, lalu build ulang.")
                return
            }
            setBusy(true)
            authScope.launch {
                try {
                    if (signup) {
                        if (SupabaseRepository.register(emailValue, passwordValue) == RegisterResult.LOGGED_IN) {
                            dialog.dismiss()
                            ui.showHome()
                        } else {
                            fail("Pendaftaran berhasil, tapi Supabase masih meminta konfirmasi email. Nonaktifkan Confirm email di Dashboard → Authentication → Providers → Email.")
                            setBusy(false)
                        }
                    } else {
                        SupabaseRepository.login(emailValue, passwordValue)
                        dialog.dismiss()
                        ui.showHome()
                    }
                } catch (e: Throwable) {
                    fail(SupabaseRepository.errorMessage(e))
                    setBusy(false)
                }
            }
        }
    }
}
