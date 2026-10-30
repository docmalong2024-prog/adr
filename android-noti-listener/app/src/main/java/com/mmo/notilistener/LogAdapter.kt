package com.mmo.notilistener

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mmo.notilistener.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(private val logList: MutableList<NotificationLog>) :
    RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val dateFormat = SimpleDateFormat("HH:mm:ss - dd/MM/yyyy", Locale.getDefault())

    inner class LogViewHolder(val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val item = logList[position]
        with(holder.binding) {
            tvLogPackage.text = item.packageName
            tvLogTime.text = dateFormat.format(Date(item.timestamp))
            tvLogContent.text = item.content

            when (item.status) {
                "SUCCESS" -> {
                    tvLogStatus.text = "THÀNH CÔNG (${item.httpCode})"
                    tvLogStatus.setTextColor(Color.parseColor("#00FF9D"))
                    tvLogStatus.setBackgroundColor(Color.parseColor("#2000FF9D"))
                    tvLogResponse.setTextColor(Color.parseColor("#00FF9D"))
                }
                "RETRYING" -> {
                    tvLogStatus.text = "ĐANG THỬ LẠI..."
                    tvLogStatus.setTextColor(Color.parseColor("#FFD000"))
                    tvLogStatus.setBackgroundColor(Color.parseColor("#20FFD000"))
                    tvLogResponse.setTextColor(Color.parseColor("#FFD000"))
                }
                else -> {
                    tvLogStatus.text = "THẤT BẠI (${item.httpCode})"
                    tvLogStatus.setTextColor(Color.parseColor("#FF0055"))
                    tvLogStatus.setBackgroundColor(Color.parseColor("#20FF0055"))
                    tvLogResponse.setTextColor(Color.parseColor("#FF0055"))
                }
            }

            tvLogResponse.text = if (item.serverResponse.isNotEmpty()) {
                "Server: ${item.serverResponse}"
            } else {
                "Server: Chưa nhận được phản hồi"
            }
        }
    }

    override fun getItemCount(): Int = logList.size

    fun addLog(log: NotificationLog) {
        logList.add(0, log)
        notifyItemInserted(0)
    }

    fun updateLog(log: NotificationLog) {
        val index = logList.indexOfFirst { it.id == log.id }
        if (index != -1) {
            logList[index] = log
            notifyItemChanged(index)
        }
    }

    fun clearAll() {
        logList.clear()
        notifyDataSetChanged()
    }
}

