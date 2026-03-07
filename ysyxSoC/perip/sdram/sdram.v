// MT48LC16M16A2 SDRAM 颗粒仿真模型
// 容量：256Mbit (16M x 16-bit)
// 组织：4 banks x 8192 rows x 512 columns x 16-bit
// 地址：ba[1:0] + row[12:0] + col[8:0]
//
// 注意：本模型适配 Verilator 零延迟仿真
// - 读数据用组合逻辑驱动（真实 SDRAM 在 negedge clk 驱动，但 Verilator
//   NBA 模型下控制器同边沿采样会晚一拍）
// - 写数据在 WRITE 命令当拍就采样（SDR SDRAM 写延迟=0）
// - 控制器 SDRAM_READ_LATENCY 需设为 3（补偿反相时钟 + 2 级采样流水线）

module sdram(
  input        clk,
  input        cke,
  input        cs,
  input        ras,
  input        cas,
  input        we,
  input [12:0] a,
  input [ 1:0] ba,
  input [ 1:0] dqm,
  inout [15:0] dq
);

  // 命令解码
  wire [3:0] cmd = {cs, ras, cas, we};

  localparam CMD_NOP        = 4'b0111;
  localparam CMD_ACTIVE     = 4'b0011;
  localparam CMD_READ       = 4'b0101;
  localparam CMD_WRITE      = 4'b0100;
  localparam CMD_PRECHARGE  = 4'b0010;
  localparam CMD_REFRESH    = 4'b0001;
  localparam CMD_LOAD_MODE  = 4'b0000;

  // 状态机
  localparam S_IDLE      = 3'd0;
  localparam S_ACTIVE    = 3'd1;
  localparam S_READ      = 3'd2;
  localparam S_READ_DATA = 3'd3;
  localparam S_WRITE     = 3'd4;

  reg [2:0]  state;
  reg [12:0] active_row [0:3];   // 每个 bank 的激活行
  reg [3:0]  row_open;           // 每个 bank 的行打开状态
  reg [1:0]  current_bank;       // 当前操作的 bank
  reg [9:0]  col_addr;           // 列地址（扩展到 10-bit 支持 512 列）
  reg [3:0]  burst_cnt;          // Burst 计数器（扩展到 4-bit 支持 BL=8）
  reg [2:0]  cas_cnt;            // CAS Latency 计数器
  reg [2:0]  cas_latency;        // 模式寄存器：CAS Latency（默认 2）
  reg [3:0]  burst_length;       // 模式寄存器：Burst Length（扩展到 4-bit 支持 BL=8）

  // DPI-C 接口声明
  import "DPI-C" function void sdram_read(
    input int unsigned addr,
    output shortint unsigned data
  );

  import "DPI-C" function void sdram_write(
    input int unsigned addr,
    input shortint unsigned data,
    input byte unsigned dqm
  );

  // DQ 双向总线
  reg [15:0] dq_out;
  reg [15:0] dq_out_next;
  reg        dq_oe;

  assign dq = dq_oe ? dq_out : 16'bz;

  // 读数据准备（组合逻辑）
  always @(*) begin
    if (state == S_READ_DATA && cke) begin
      sdram_read({7'b0, active_row[current_bank], current_bank, col_addr}, dq_out_next);
    end else begin
      dq_out_next = 16'b0;
    end
  end

  // 读数据输出（组合逻辑驱动）
  always @(*) begin
    if (state == S_READ_DATA && cke) begin
      dq_oe = 1'b1;
      dq_out = dq_out_next;
    end else begin
      dq_oe = 1'b0;
      dq_out = 16'b0;
    end
  end

  // 写采样信号：S_ACTIVE 收到 WRITE 命令的当拍 或 S_WRITE 状态
  wire write_sample = cke && (
    (state == S_ACTIVE && cmd == CMD_WRITE) ||  // 第一个 beat：WRITE 命令当拍
    (state == S_WRITE)                           // 后续 beat
  );

  // 写地址：WRITE 命令当拍用 a[9:0]，后续用 col_addr（已递增）
  wire [9:0] write_col = (state == S_ACTIVE) ? a[9:0] : col_addr;
  wire [1:0] write_bank = (state == S_ACTIVE) ? ba : current_bank;

  // 写数据采样（时钟上升沿采样 dq 输入）
  always @(posedge clk) begin
    if (write_sample) begin
      sdram_write({7'b0, active_row[write_bank], write_bank, write_col}, dq, {6'b0, dqm});
    end
  end

  // 初始化
  integer i;
  initial begin
    state = S_IDLE;
    cas_latency = 3'd2;
    burst_length = 4'd2;
    row_open = 4'b0000;
    for (i = 0; i < 4; i = i + 1) begin
      active_row[i] = 13'b0;
    end
    current_bank = 2'b0;
    col_addr = 10'b0;
    burst_cnt = 4'b0;
    cas_cnt = 3'b0;
  end

  // 状态机
  always @(posedge clk) begin
    if (!cke) begin
      // CKE=0：保持当前状态
    end else begin
      case (state)
        S_IDLE: begin
          case (cmd)
            CMD_ACTIVE: begin
              active_row[ba] <= a;
              row_open[ba] <= 1'b1;
              current_bank <= ba;
              state <= S_ACTIVE;
            end
            CMD_LOAD_MODE: begin
              cas_latency <= a[6:4];
              case (a[2:0])
                3'd0: burst_length <= 4'd1;
                3'd1: burst_length <= 4'd2;
                3'd2: burst_length <= 4'd4;
                3'd3: burst_length <= 4'd8;
                default: burst_length <= 4'd2;
              endcase
            end
            CMD_PRECHARGE: begin
              if (a[10])
                row_open <= 4'b0000;
              else
                row_open[ba] <= 1'b0;
            end
            CMD_REFRESH: begin
              // 简化：当作 NOP
            end
            default: begin
              // NOP
            end
          endcase
        end

        S_ACTIVE: begin
          case (cmd)
            CMD_READ: begin
              col_addr <= a[9:0];
              cas_cnt <= cas_latency;
              burst_cnt <= burst_length;
              state <= S_READ;
            end
            CMD_WRITE: begin
              col_addr <= a[9:0] + 1;  // 第一个 beat 已在当拍采样，列地址提前递增
              burst_cnt <= burst_length - 1;  // 第一个 beat 已消费
              if (burst_length <= 1)
                state <= S_IDLE;
              else
                state <= S_WRITE;
            end
            CMD_PRECHARGE: begin
              if (a[10])
                row_open <= 4'b0000;
              else
                row_open[current_bank] <= 1'b0;
              state <= S_IDLE;
            end
            default: begin
              // 保持 ACTIVE
            end
          endcase
        end

        S_READ: begin
          if (cas_cnt > 1)
            cas_cnt <= cas_cnt - 1;
          else
            state <= S_READ_DATA;
        end

        S_READ_DATA: begin
          if (burst_cnt > 1) begin
            col_addr <= col_addr + 1;
            burst_cnt <= burst_cnt - 1;
          end else begin
            state <= S_ACTIVE;  // 行仍然打开，回到 ACTIVE 等待下一个命令
          end
        end

        S_WRITE: begin
          if (burst_cnt > 1) begin
            col_addr <= col_addr + 1;
            burst_cnt <= burst_cnt - 1;
          end else begin
            state <= S_ACTIVE;  // 行仍然打开，回到 ACTIVE 等待下一个命令
          end
        end

        default: begin
          state <= S_IDLE;
        end
      endcase
    end
  end

endmodule
