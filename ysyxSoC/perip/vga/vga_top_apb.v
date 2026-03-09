module vga_top_apb(
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

  output [7:0]  vga_r,
  output [7:0]  vga_g,
  output [7:0]  vga_b,
  output        vga_hsync,
  output        vga_vsync,
  output        vga_valid
);

  localparam [31:0] FB_BASE = 32'h2100_0000;

  localparam [9:0] H_SYNC_PULSE    = 10'd96;
  localparam [9:0] H_VISIBLE_START = 10'd144;
  localparam [9:0] H_VISIBLE_END   = 10'd784;
  localparam [9:0] H_LAST          = 10'd799;

  localparam [9:0] V_SYNC_PULSE    = 10'd2;
  localparam [9:0] V_VISIBLE_START = 10'd35;
  localparam [9:0] V_VISIBLE_END   = 10'd515;
  localparam [9:0] V_LAST          = 10'd524;

  localparam [18:0] FB_WORDS      = 19'd307200;
  localparam [18:0] FB_LAST_INDEX = 19'd307199;

  reg [31:0] fb_mem [0:FB_WORDS - 1];
  reg [9:0] h_count;
  reg [9:0] v_count;
  reg [18:0] scan_index;

  integer init_idx;
  initial begin
    for (init_idx = 0; init_idx < FB_WORDS; init_idx = init_idx + 1) begin
      fb_mem[init_idx] = 32'h0000_0000;
    end
  end

  wire apb_setup = in_psel & ~in_penable;
  wire apb_read  = ~reset & in_psel & ~in_pwrite;
  wire apb_write = ~reset & apb_setup &  in_pwrite;

  wire [31:0] fb_addr_offset = in_paddr - FB_BASE;
  wire [18:0] fb_word_index = fb_addr_offset[20:2];
  wire        fb_addr_aligned = (in_paddr[1:0] == 2'b00);
  wire        fb_addr_hit = fb_addr_aligned && (fb_word_index < FB_WORDS);

  reg [31:0] apb_rdata;
  reg [31:0] fb_wdata;

  wire visible_x_hit = (h_count >= H_VISIBLE_START) && (h_count < H_VISIBLE_END);
  wire visible_y_hit = (v_count >= V_VISIBLE_START) && (v_count < V_VISIBLE_END);
  wire display_active = visible_x_hit && visible_y_hit;
  wire [31:0] scan_pixel = display_active ? fb_mem[scan_index] : 32'h0000_0000;

  always @(*) begin
    if (fb_addr_hit) begin
      fb_wdata = fb_mem[fb_word_index];
    end else begin
      fb_wdata = 32'h0000_0000;
    end
    if (in_pstrb[0]) fb_wdata[7:0]   = in_pwdata[7:0];
    if (in_pstrb[1]) fb_wdata[15:8]  = in_pwdata[15:8];
    if (in_pstrb[2]) fb_wdata[23:16] = in_pwdata[23:16];
    if (in_pstrb[3]) fb_wdata[31:24] = in_pwdata[31:24];
  end

  always @(posedge clock) begin
    if (reset) begin
      h_count <= 10'd0;
      v_count <= 10'd0;
      scan_index <= 19'd0;
    end else begin
      if (h_count == H_LAST) begin
        h_count <= 10'd0;
        if (v_count == V_LAST) begin
          v_count <= 10'd0;
        end else begin
          v_count <= v_count + 10'd1;
        end
      end else begin
        h_count <= h_count + 10'd1;
      end

      if (display_active) begin
        if (scan_index == FB_LAST_INDEX) begin
          scan_index <= 19'd0;
        end else begin
          scan_index <= scan_index + 19'd1;
        end
      end

      if (apb_write && fb_addr_hit) begin
        fb_mem[fb_word_index] <= fb_wdata;
      end
    end
  end

  always @(*) begin
    apb_rdata = 32'h0000_0000;
    if (apb_read && fb_addr_hit) begin
      apb_rdata = fb_mem[fb_word_index];
    end
  end

  assign in_pready = in_psel & in_penable;
  assign in_prdata = apb_rdata;
  assign in_pslverr = 1'b0;

  assign vga_hsync = ~(h_count < H_SYNC_PULSE);
  assign vga_vsync = ~(v_count < V_SYNC_PULSE);
  assign vga_valid = display_active;
  assign vga_r = display_active ? scan_pixel[23:16] : 8'h00;
  assign vga_g = display_active ? scan_pixel[15:8]  : 8'h00;
  assign vga_b = display_active ? scan_pixel[7:0]   : 8'h00;

endmodule
