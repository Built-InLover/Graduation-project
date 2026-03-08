module ps2_top_apb(
  input         clock,
  input         reset,
  input  [31:0] in_paddr,
  input         in_psel,
  input         in_penable,
  input  [2:0]  in_pprot,
  input         in_pwrite,
  input  [31:0] in_pwdata,
  input  [3:0]  in_pstrb,
  output        in_pready,
  output [31:0] in_prdata,
  output        in_pslverr,

  input         ps2_clk,
  input         ps2_data
);

  localparam FIFO_DEPTH = 16;

  reg [9:0] frame;
  reg [3:0] bit_count;
  reg [2:0] ps2_clk_sync;
  reg       receiving;

  reg [7:0] fifo_mem [0:FIFO_DEPTH-1];
  reg [3:0] fifo_wptr;
  reg [3:0] fifo_rptr;
  reg [4:0] fifo_count;

  reg       push_now;
  reg       pop_now;
  reg [7:0] push_data;

  wire apb_access = in_psel & in_penable;
  wire reg_read_req = ~reset & apb_access & ~in_pwrite & (in_paddr[2:0] == 3'b000);
  wire reg_read_data = reg_read_req & (fifo_count != 0);
  wire sampling = ps2_clk_sync[2] & ~ps2_clk_sync[1];
  wire [7:0] fifo_head = (fifo_count != 0) ? fifo_mem[fifo_rptr] : 8'h00;

  always @(posedge clock) begin
    if (reset) begin
      frame <= 10'h000;
      bit_count <= 4'h0;
      ps2_clk_sync <= 3'b111;
      receiving <= 1'b0;
      fifo_wptr <= 4'h0;
      fifo_rptr <= 4'h0;
      fifo_count <= 5'h00;
    end else begin
      ps2_clk_sync <= {ps2_clk_sync[1:0], ps2_clk};

      push_now = 1'b0;
      pop_now = reg_read_data;
      push_data = 8'h00;

      if (sampling) begin
        if (!receiving) begin
          if (!ps2_data) begin
            frame[0] <= 1'b0;
            bit_count <= 4'd1;
            receiving <= 1'b1;
          end
        end else if (bit_count == 4'd10) begin
          if ((frame[0] == 1'b0) && ps2_data && (^frame[9:1])) begin
            push_now = 1'b1;
            push_data = frame[8:1];
          end
          bit_count <= 4'd0;
          receiving <= 1'b0;
        end else begin
          frame[bit_count] <= ps2_data;
          bit_count <= bit_count + 1'b1;
        end
      end


      case ({push_now, pop_now})
        2'b10: begin
          if (fifo_count < FIFO_DEPTH) begin
            fifo_mem[fifo_wptr] <= push_data;
            fifo_wptr <= fifo_wptr + 1'b1;
            fifo_count <= fifo_count + 1'b1;
          end
        end
        2'b01: begin
          fifo_rptr <= fifo_rptr + 1'b1;
          fifo_count <= fifo_count - 1'b1;
        end
        2'b11: begin
          if (fifo_count == 0) begin
            fifo_mem[fifo_wptr] <= push_data;
            fifo_wptr <= fifo_wptr + 1'b1;
            fifo_count <= 5'd1;
          end else begin
            fifo_mem[fifo_wptr] <= push_data;
            fifo_wptr <= fifo_wptr + 1'b1;
            fifo_rptr <= fifo_rptr + 1'b1;
          end
        end
        default: begin
        end
      endcase
    end
  end

  assign in_pready = in_psel & in_penable;
  assign in_pslverr = 1'b0;
  assign in_prdata = (in_paddr[2:0] == 3'b000) ? {24'h0, fifo_head} : 32'h00000000;

endmodule
