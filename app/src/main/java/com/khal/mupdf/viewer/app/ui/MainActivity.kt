package com.khal.mupdf.viewer.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.khal.mupdf.viewer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!! // Property only valid between onCreateView & onDestroy.


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    public override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

}