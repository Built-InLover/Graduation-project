// QSPI/QPI PSRAM 颗粒仿真行为模型
// 支持命令：EBh（Quad IO Read）、38h（Quad IO Write）、35h（Enter QPI Mode）
// QSPI：CMD 1-bit 串行，ADDR/DATA 4-bit/cycle
// QPI ：CMD/ADDR/DATA 全部 4-bit/cycle
//
// 时序：
//   sck posedge 采样输入（CMD/ADDR/写DATA）
//   sck negedge 驱动读DATA输出
//   ce_n 上升沿复位状态机（异步复位）

`timescale 1ns/1ps
`default_nettype none

module psram(
  input  wire        sck,
  input  wire        ce_n,
  inout  wire [3:0]  dio
);

  // 存储阵列通过 DPI-C 实现（稀疏，避免 4MB 静态 reg）
  import "DPI-C" function void psram_write(input int addr, input byte data);
  import "DPI-C" function byte psram_read(input int addr);

  // 状态机
  localparam S_IDLE  = 3'd0;
  localparam S_CMD   = 3'd1;
  localparam S_ADDR  = 3'd2;
  localparam S_DUMMY = 3'd3;
  localparam S_RDATA = 3'd4;
  localparam S_WDATA = 3'd5;

  reg [2:0]  state;
  reg [3:0]  cnt;        // 通用计数器（posedge 递增）
  reg [7:0]  cmd_reg;    // 接收到的命令字节
  reg [31:0] addr_reg;   // 接收到的地址（32-bit，传给 DPI-C）
  reg [3:0]  dout_reg;   // 读数据输出寄存器
  reg        oe;         // 输出使能

  // QPI 模式标志
  reg        qpi_mode;
  reg        qpi_pending;

  // 读数据缓冲（4字节）
  reg [7:0]  rdata [0:3];
  reg [3:0]  rnibs_cnt;  // 已输出 nibble 数（negedge 递增）

  // 写数据移位寄存器（每 posedge 移入 4-bit，存前 7 个 nibble）
  reg [27:0] wshift;
  reg [3:0]  wcnt;       // 写数据 nibble 计数（0~7）

  // 三态输出
  assign dio = oe ? dout_reg : 4'bz;

  // -------------------------------------------------------
  // sck posedge：采样输入（CMD/ADDR/写DATA）
  // ce_n 上升沿异步复位
  // -------------------------------------------------------
  always @(posedge sck or posedge ce_n) begin
    if (ce_n) begin
      // 异步复位：ce_n 拉高时复位状态机
      // 如果正在写数据且已收到完整字节，提交写入
      if (state == S_WDATA) begin
        case (wcnt)
          4'd2: begin
            byte wb0;
            wb0 = {wshift[7:4], wshift[3:0]};
            psram_write({8'b0, addr_reg[23:0]}, wb0);
          end
          4'd4: begin
            byte wb0, wb1;
            wb0 = {wshift[15:12], wshift[11:8]};
            wb1 = {wshift[7:4],   wshift[3:0]};
            psram_write({8'b0, addr_reg[23:0]},         wb0);
            psram_write({8'b0, addr_reg[23:0]} + 32'd1, wb1);
          end
          4'd6: begin
            byte wb0, wb1, wb2;
            wb0 = {wshift[23:20], wshift[19:16]};
            wb1 = {wshift[15:12], wshift[11:8]};
            wb2 = {wshift[7:4],   wshift[3:0]};
            psram_write({8'b0, addr_reg[23:0]},         wb0);
            psram_write({8'b0, addr_reg[23:0]} + 32'd1, wb1);
            psram_write({8'b0, addr_reg[23:0]} + 32'd2, wb2);
          end
          default: ; // wcnt 为奇数或 0，不提交
        endcase
      end
      state    <= S_IDLE;
      cnt      <= 4'd0;
      if (qpi_pending) begin
        qpi_mode    <= 1'b1;
        qpi_pending <= 1'b0;
      end
    end else begin
      // sck posedge：采样
      case (state)

        // ---- S_IDLE：第一个 posedge，开始接收 CMD ----
        S_IDLE: begin
          state <= S_CMD;
          if (qpi_mode) begin
            cmd_reg <= {4'b0, dio};
            cnt     <= 4'd1;
          end else begin
            cmd_reg <= {7'b0, dio[0]};
            cnt     <= 4'd1;
          end
        end

        // ---- S_CMD：继续接收 CMD ----
        S_CMD: begin
          if (qpi_mode) begin
            cmd_reg <= {cmd_reg[3:0], dio};
            if (cnt == 4'd1) begin
              state <= S_ADDR;
              cnt   <= 4'd0;
            end else begin
              cnt <= cnt + 4'd1;
            end
          end else begin
            cmd_reg <= {cmd_reg[6:0], dio[0]};
            if (cnt == 4'd7) begin
              cnt <= 4'd0;
              if ({cmd_reg[6:0], dio[0]} == 8'h35) begin
                // 35h：Enter QPI，单字节命令，无地址/数据
                qpi_pending <= 1'b1;
                state       <= S_IDLE;
              end else begin
                state <= S_ADDR;
              end
            end else begin
              cnt <= cnt + 4'd1;
            end
          end
        end

        // ---- S_ADDR：4-bit/cycle，6 cycle = 24-bit 地址 ----
        S_ADDR: begin
          addr_reg <= {addr_reg[27:0], dio};
          if (cnt == 4'd5) begin
            cnt <= 4'd0;
            case (cmd_reg)
              8'hEB: state <= S_DUMMY;
              8'h38: begin
                state  <= S_WDATA;
                wcnt   <= 4'd0;
                wshift <= 28'd0;
              end
              default: state <= S_IDLE;
            endcase
          end else begin
            cnt <= cnt + 4'd1;
          end
        end

        // ---- S_DUMMY：6 dummy cycle ----
        S_DUMMY: begin
          if (cnt == 4'd5) begin
            cnt      <= 4'd0;
            state    <= S_RDATA;
            rdata[0] = psram_read({8'b0, addr_reg[23:0]});
            rdata[1] = psram_read({8'b0, addr_reg[23:0]} + 32'd1);
            rdata[2] = psram_read({8'b0, addr_reg[23:0]} + 32'd2);
            rdata[3] = psram_read({8'b0, addr_reg[23:0]} + 32'd3);
          end else begin
            cnt <= cnt + 4'd1;
          end
        end

        // ---- S_RDATA：读数据阶段，posedge 仅计数 ----
        S_RDATA: begin
          cnt <= cnt + 4'd1;
        end

        // ---- S_WDATA：4-bit/cycle，8 cycle = 32-bit 数据 ----
        // 字节序：nibble0=byte0[7:4], nibble1=byte0[3:0], nibble2=byte1[7:4]...
        S_WDATA: begin
          if (wcnt < 4'd7) begin
            wshift <= {wshift[23:0], dio};
            wcnt   <= wcnt + 4'd1;
          end else begin
            // 第 8 个 nibble，组合完整 32-bit 并写入
            // wshift = {nibble0, nibble1, nibble2, nibble3, nibble4, nibble5, nibble6}
            // dio    = nibble7
            begin
              byte b0, b1, b2, b3;
              b0 = {wshift[27:24], wshift[23:20]};
              b1 = {wshift[19:16], wshift[15:12]};
              b2 = {wshift[11:8],  wshift[7:4]};
              b3 = {wshift[3:0],   dio};
              psram_write({8'b0, addr_reg[23:0]},          b0);
              psram_write({8'b0, addr_reg[23:0]} + 32'd1,  b1);
              psram_write({8'b0, addr_reg[23:0]} + 32'd2,  b2);
              psram_write({8'b0, addr_reg[23:0]} + 32'd3,  b3);
            end
            state <= S_IDLE;
          end
        end

        default: state <= S_IDLE;
      endcase
    end
  end

  // -------------------------------------------------------
  // sck negedge：驱动读数据输出
  // ce_n 上升沿异步复位输出使能
  // -------------------------------------------------------
  always @(negedge sck or posedge ce_n) begin
    if (ce_n) begin
      oe        <= 1'b0;
      dout_reg  <= 4'b0;
      rnibs_cnt <= 4'd0;
    end else begin
      if (state == S_RDATA) begin
        case (rnibs_cnt)
          4'd0: dout_reg <= rdata[0][7:4];
          4'd1: dout_reg <= rdata[0][3:0];
          4'd2: dout_reg <= rdata[1][7:4];
          4'd3: dout_reg <= rdata[1][3:0];
          4'd4: dout_reg <= rdata[2][7:4];
          4'd5: dout_reg <= rdata[2][3:0];
          4'd6: dout_reg <= rdata[3][7:4];
          4'd7: dout_reg <= rdata[3][3:0];
          default: dout_reg <= 4'b0;
        endcase
        oe        <= 1'b1;
        rnibs_cnt <= rnibs_cnt + 4'd1;
      end
    end
  end

  // -------------------------------------------------------
  // 初始化
  // -------------------------------------------------------
  initial begin
    state       = S_IDLE;
    cnt         = 4'd0;
    cmd_reg     = 8'd0;
    addr_reg    = 32'd0;
    dout_reg    = 4'd0;
    oe          = 1'b0;
    qpi_mode    = 1'b0;
    qpi_pending = 1'b0;
    wcnt        = 4'd0;
    wshift      = 28'd0;
    rnibs_cnt   = 4'd0;
  end

endmodule
