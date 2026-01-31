package common

import chisel3._
import chisel3.util._

class ForwardingBus extends Bundle {
  // 1. 占位信号：表示该部件里确实有一条指令要写寄存器
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
  /**
    * @param current_id 当前 IDU 指令的 ID
    * @param rs_addr    当前指令想读的寄存器地址
    * @param default_data 从 RegFile 读出来的原始数据 (保底数据)
    * @param sources    所有的转发源 (来自 Top)
    */
  def apply(current_id: UInt, rs_addr: UInt, default_data: UInt, sources: Seq[ForwardingBus]): (UInt, Bool) = {
    
    // =================================================================
    // 1. 筛选阶段：计算距离并排除无效源
    // =================================================================
    val candidates = sources.map { s =>
      // 计算环形距离 (Chisel 会自动处理溢出，比如 2 - 1 = 1, 2 - 2 = 0)
      val distance = (current_id - s.uop_id)
      
      // [核心修复] 必须排除距离为 0 的情况！
      // distance === 0 意味着 source 的 ID 和当前 ID 一样。
      // 在 pipe=true 的队列中，这代表当前指令“看见了”它自己。
      // 我们必须忽略自己，否则会形成组合逻辑环路或读到错误数据。
      val is_not_self = distance =/= 0.U

      // 命中条件：
      // 1. source 确实有写回请求 (pend)
      // 2. 寄存器号对得上
      // 3. 不是读 x0
      // 4. [新增] 不是自己 (is_not_self)
      val match_reg = s.pend && (s.rdAddr === rs_addr) && (rs_addr =/= 0.U) && is_not_self
      
      (match_reg, distance, s)
    }

    // =================================================================
    // 2. 竞选阶段 (寻找最近的命中源)
    // =================================================================
    // 使用 reduceLeft 对所有候选项进行两两比较
    val best_match = candidates.reduceLeft { (res, next) =>
      // --- 1. 解包 (Unpack) ---
      val (res_match, res_dist, res_bus) = res
      val (next_match, next_dist, next_bus) = next
      
      // --- 2. 仲裁逻辑 ---
      // 谁赢？
      // 如果 next 匹配了，并且 (res 没匹配 或者 next 的距离更小/更新)，next 赢
      val next_is_better = next_match && (!res_match || (next_dist < res_dist))
      
      // --- 3. Mux 选出赢家 ---
      val out_match = Mux(next_is_better, next_match, res_match)
      val out_dist  = Mux(next_is_better, next_dist,  res_dist)
      val out_bus   = Mux(next_is_better, next_bus,   res_bus) 
      
      // --- 4. 重新打包传给下一轮 ---
      (out_match, out_dist, out_bus)
    }

    // 解包最终赢家
    val (has_hit, _, best_source) = best_match

    // =================================================================
    // 3. 决策阶段
    // =================================================================
    
    // 最终数据：
    // 如果命中 (has_hit) 且数据已就绪 (valid)，则使用转发数据
    // 否则使用 RegFile 的旧数据 (default_data)
    // 注意：如果需要 Stall，这里的数据其实会被忽略，但为了逻辑完备，选 RegFile 值或转发值都可以
    val final_data = Mux(has_hit && best_source.valid, best_source.data, default_data)
    
    // 暂停请求：
    // 如果命中 (has_hit) 但数据还没好 (!valid)，说明是 Load-Use 或 长周期运算
    // 必须暂停 IDU，等待数据 Ready
    val stall_req  = has_hit && !best_source.valid

    (final_data, stall_req)
  }
}