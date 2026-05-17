package com.example.yolodetector

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF, // กรอบที่ตรวจจับได้
    val classIndex: Int,    // ลำดับของ Class (เช่น 0 คือ person)
    val score: Float        // คะแนนความเชื่อมั่น (Confidence)
)