package common

import chisel3._
import chisel3.util._

class ForwardingBus extends Bundle {
  // 1. 占位信号：表示该部件里确实有一条指令要写这个寄存器
  // 无论数据是否准备好，这个信号都必须为 true
  val pend     = Bool()   
  // 2. 数据有效信号：表示 data 字段现在是有效的
  val valid    = Bool()
  // 3. 身份与数据
  val uop_id   = UInt(4.W) // 必须带 ID 才能区分先后
  val rdAddr   = UInt(5.W)
  val data     = UInt(32.W)
}

// 2. 定义工具逻辑（单例对象）
object ForwardingChoose {
  def apply(current_id: UInt, rs_addr: UInt, default_data: UInt, sources: Seq[ForwardingBus]): (UInt, Bool) = {
    // 1. 筛选阶段：只看 Pend 信号和地址匹配
    val candidates = sources.map { s =>
      val match_reg = s.pend && (s.rdAddr === rs_addr) && (rs_addr =/= 0.U)
      val distance  = (current_id - s.uop_id) // 距离越小越新 Chisel 的 UInt 减法会自动处理回绕(比如 1 - 15 = 2)
      (match_reg, distance, s)
    }

    // 2. 竞选阶段
    // 语法: reduceLeft 是一个归约操作。
    // 假设有 [A, B, C, D] 四个候选项。
    // 第一轮：比较 A 和 B，胜者叫 Win1。
    // 第二轮：比较 Win1 和 C，胜者叫 Win2。
    // 第三轮：比较 Win2 和 D，最终胜者就是 best_match。
    val best_match = candidates.reduceLeft { (res, next) =>
      // --- 1. 解包 (Unpack) ---
      val (res_match, res_dist, res_bus) = res
      val (next_match, next_dist, next_bus) = next
      
      // --- 2. 仲裁逻辑 ---
      // 如果 next 匹配，且 (res 没匹配 或者 next 更近)，那就选 next
      val next_is_better = next_match && (!res_match || (next_dist < res_dist))
      
      // --- 3. 分别 Mux (关键修正) ---
      // Mux 不能直接吃元组，必须喂给它具体的硬件类型
      val out_match = Mux(next_is_better, next_match, res_match)
      val out_dist  = Mux(next_is_better, next_dist,  res_dist)
      val out_bus   = Mux(next_is_better, next_bus,   res_bus) // ForwardingBus 是 Bundle，可以直接 Mux
      
      // --- 4. 重新打包 (Repack) ---
      // 把选出来的信号包好，传给下一轮比较
      (out_match, out_dist, out_bus)
    }

    //"_"是占位符。意思是“这里有个东西，但我不在乎它是啥，也不想给它起名字，直接跳过”
    val (has_hit, _, best_source) = best_match

    // 3. 决策阶段
    // 如果没有命中任何 Pend，就用 RegFile 里的旧数据 (default_data)
    // 如果命中了 Pend：
    //    -> 如果 best_source.valid 为真，旁路成功，使用 best_source.data
    //    -> 如果 best_source.valid 为假，说明命中但数据未好，必须 Stall
    
    val final_data = Mux(has_hit && best_source.valid, best_source.data, default_data)
    val stall_req  = has_hit && !best_source.valid

    (final_data, stall_req)
  }
}