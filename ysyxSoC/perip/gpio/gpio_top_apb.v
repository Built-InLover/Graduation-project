module gpio_top_apb(
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

  output [15:0] gpio_out,
  input  [15:0] gpio_in,
  output [7:0]  gpio_seg_0,
  output [7:0]  gpio_seg_1,
  output [7:0]  gpio_seg_2,
  output [7:0]  gpio_seg_3,
  output [7:0]  gpio_seg_4,
  output [7:0]  gpio_seg_5,
  output [7:0]  gpio_seg_6,
  output [7:0]  gpio_seg_7
);

  reg [15:0] led_reg;

  wire apb_setup = in_psel & ~in_penable;
  wire reg_we = ~reset & apb_setup & in_pwrite;

  wire [15:0] led_wdata = {
    (in_pstrb[1] ? in_pwdata[15:8] : led_reg[15:8]),
    (in_pstrb[0] ? in_pwdata[7:0]  : led_reg[7:0])
  };

  always @(posedge clock) begin
    if (reset) begin
      led_reg <= 16'h0000;
    end else if (reg_we && (in_paddr[3:2] == 2'b00)) begin
      led_reg <= led_wdata;
    end
  end

  assign in_pready = in_psel & in_penable;
  assign in_pslverr = 1'b0;
  assign in_prdata =
    (in_paddr[3:2] == 2'b00) ? {16'h0000, led_reg} :
    (in_paddr[3:2] == 2'b01) ? {16'h0000, gpio_in} :
                               32'h00000000;

  assign gpio_out = led_reg;
  assign gpio_seg_0 = 8'hff;
  assign gpio_seg_1 = 8'hff;
  assign gpio_seg_2 = 8'hff;
  assign gpio_seg_3 = 8'hff;
  assign gpio_seg_4 = 8'hff;
  assign gpio_seg_5 = 8'hff;
  assign gpio_seg_6 = 8'hff;
  assign gpio_seg_7 = 8'hff;

endmodule
