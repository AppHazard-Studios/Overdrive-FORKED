package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.overdrive.app.R
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class RemoteAccessFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()

    private lateinit var switchAccessMode: SwitchMaterial
    private lateinit var tvAccessMode: TextView
    private lateinit var urlStatusDot: View
    private lateinit var tvCurrentUrl: TextView
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: MaterialButton

    private var isTokenVisible = false
    private var isUpdatingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_remote_access, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        setupAccessModeToggle()
        observeViewModels()
        loadAuthState()
    }

    private fun initViews(view: View) {
        switchAccessMode    = view.findViewById(R.id.switchAccessMode)
        tvAccessMode        = view.findViewById(R.id.tvAccessMode)
        urlStatusDot        = view.findViewById(R.id.urlStatusDot)
        tvCurrentUrl        = view.findViewById(R.id.tvCurrentUrl)
        tvDeviceToken       = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken      = view.findViewById(R.id.btnToggleToken)
        btnCopyToken        = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken  = view.findViewById(R.id.btnRegenerateToken)
    }

    private fun setupClickListeners() {
        view?.findViewById<View>(R.id.btnCopyUrl)?.setOnClickListener {
            val url = mainViewModel.currentUrl.value
            if (!url.isNullOrEmpty()) {
                val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
                Toast.makeText(requireContext(), "URL copied", Toast.LENGTH_SHORT).show()
            }
        }
        btnToggleToken.setOnClickListener    { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener      { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
    }

    private fun setupAccessModeToggle() {
        switchAccessMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            val mode = if (isChecked) AccessMode.PUBLIC else AccessMode.PRIVATE
            mainViewModel.setAccessMode(mode)
            daemonsViewModel.daemonStartupManager?.onAccessModeChanged(mode)
        }
    }

    private fun observeViewModels() {
        mainViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            if (url.isNullOrEmpty()) {
                tvCurrentUrl.text = "No tunnel running"
                urlStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
            } else {
                tvCurrentUrl.text = url
                urlStatusDot.setBackgroundResource(R.drawable.status_dot_online)
            }
        }
        mainViewModel.accessMode.observe(viewLifecycleOwner) { mode ->
            isUpdatingSwitch = true
            switchAccessMode.isChecked = mode == AccessMode.PUBLIC
            isUpdatingSwitch = false
            tvAccessMode.text = mode.name
        }
    }

    private fun loadAuthState() {
        try {
            val state = AuthManager.getState()
            if (state != null) updateTokenDisplay(state.secret)
            else { AuthManager.initialize(); loadAuthState() }
        } catch (_: Exception) {
            tvDeviceToken.text = "••••••••"
        }
    }

    private fun updateTokenDisplay(secret: String) {
        tvDeviceToken.text = if (isTokenVisible) secret else "••••••••"
    }

    private fun toggleTokenVisibility() {
        isTokenVisible = !isTokenVisible
        AuthManager.getState()?.let { updateTokenDisplay(it.secret) }
        btnToggleToken.setImageResource(
            if (isTokenVisible) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_view
        )
    }

    private fun copyTokenToClipboard() {
        val state = AuthManager.getState() ?: return
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Access Code", state.secret))
        Toast.makeText(requireContext(), "Access code copied", Toast.LENGTH_SHORT).show()
    }

    private fun showRegenerateConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Regenerate Token")
            .setMessage("This will invalidate the current token. All active sessions will be logged out. Continue?")
            .setPositiveButton("Regenerate") { _, _ -> regenerateToken() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun regenerateToken() {
        AuthManager.regenerateToken()
        Thread {
            try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    val success = client.invalidateAuthCacheSync()
                    client.disconnect()
                    activity?.runOnUiThread {
                        val msg = if (success)
                            "New token generated. All sessions logged out."
                        else
                            "Token regenerated. Daemon may need restart to apply."
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Token regenerated. Could not notify daemon.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Token regenerated", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        loadAuthState()
    }
}
