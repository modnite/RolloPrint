package com.example.rolloprint

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton

class PrintPreviewDialogFragment : DialogFragment() {

    interface OnConfirmListener {
        fun onConfirm()
    }

    private var onConfirmListener: OnConfirmListener? = null
    private var originalBitmap: Bitmap? = null

    companion object {
        fun newInstance(bitmap: Bitmap, listener: OnConfirmListener): PrintPreviewDialogFragment {
            return PrintPreviewDialogFragment().apply {
                originalBitmap = bitmap
                onConfirmListener = listener
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_print_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState);
        
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirm)

        ivPreview.setImageBitmap(originalBitmap)

        btnCancel.setOnClickListener { dismiss() }

        btnConfirm.setOnClickListener {
            onConfirmListener?.onConfirm()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
