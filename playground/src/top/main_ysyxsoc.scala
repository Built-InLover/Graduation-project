package top

object main_ysyxsoc extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _),
  )

  val customArgs = Array(
    "--target-dir", "build"
  )

  // 生成符合 ysyxSoC 规范的 CPU 模块
  circt.stage.ChiselStage.emitSystemVerilogFile(new top.ysyx_23060000(), customArgs, firtoolOptions)
}
