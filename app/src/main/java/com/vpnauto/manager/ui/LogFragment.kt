package com.vpnauto.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.FragmentLogBinding
import com.vpnauto.manager.service.ConnectionLog
import com.vpnauto.manager.service.LogLevel

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCopyLog.setOnClickListener {
            val text = ConnectionLog.getText()
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vpn_log", text))
            Toast.makeText(requireContext(), "Лог скопирован", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLog.setOnClickListener {
            ConnectionLog.clear()
        }

        // Наблюдаем за логом
        ConnectionLog.entries.observe(viewLifecycleOwner) { entries ->
            val sb = SpannableStringBuilder()
            entries.forEach { entry ->
                val color = when (entry.level) {
                    LogLevel.OK    -> Color.parseColor("#81C784")
                    LogLevel.WARN  -> Color.parseColor("#FFB74D")
                    LogLevel.ERROR -> Color.parseColor("#EF5350")
                    LogLevel.INFO  -> Color.parseColor("#B0BEC5")
                }
                val line = "[${entry.time}] ${entry.message}\n"
                val start = sb.length
                sb.append(line)
                sb.setSpan(ForegroundColorSpan(color), start, sb.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.tvLog.setText(sb, TextView.BufferType.SPANNABLE)
            // Авто-скролл вниз
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
