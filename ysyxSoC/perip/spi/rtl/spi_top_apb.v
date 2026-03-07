// define this macro to enable fast behavior simulation
// for flash by skipping SPI transfers
//`define FAST_FLASH

module spi_top_apb #(
  parameter flash_addr_start = 32'h30000000,
  parameter flash_addr_end   = 32'h3fffffff,
  parameter spi_ss_num       = 8
) (
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

  output                  spi_sck,
  output [spi_ss_num-1:0] spi_ss,
  output                  spi_mosi,
  input                   spi_miso,
  output                  spi_irq_out
);

`ifdef FAST_FLASH

wire [31:0] data;
parameter invalid_cmd = 8'h0;
flash_cmd flash_cmd_i(
  .clock(clock),
  .valid(in_psel && !in_penable),
  .cmd(in_pwrite ? invalid_cmd : 8'h03),
  .addr({8'b0, in_paddr[23:2], 2'b0}),
  .data(data)
);
assign spi_sck    = 1'b0;
assign spi_ss     = 8'b0;
assign spi_mosi   = 1'b1;
assign spi_irq_out= 1'b0;
assign in_pslverr = 1'b0;
assign in_pready  = in_penable && in_psel && !in_pwrite;
assign in_prdata  = data[31:0];

`else

// --- XIP (Execute In Place) state machine ---
wire is_flash = (in_paddr >= flash_addr_start) && (in_paddr <= flash_addr_end);

// Flash write protection
always @(posedge clock) begin
  if (is_flash && in_psel && in_pwrite) begin
    $fwrite(32'h80000002, "XIP: write to flash address 0x%08x is forbidden!\n", in_paddr);
    $fatal;
  end
end

// XIP state encoding
localparam S_IDLE    = 4'd0;
localparam S_WR_TX1  = 4'd1;
localparam S_WR_TX0  = 4'd2;
localparam S_WR_SS   = 4'd3;
localparam S_WR_CTRL = 4'd4;
localparam S_POLL    = 4'd5;
localparam S_RD_RX0  = 4'd6;
localparam S_CLR_SS  = 4'd7;
localparam S_DONE    = 4'd8;

reg [3:0]  xip_state;
reg [23:0] xip_addr;
reg [31:0] xip_rdata;

// Wishbone signals from state machine
reg  [4:0]  xip_wb_adr;
reg  [31:0] xip_wb_dat;
reg         xip_wb_we;
reg         xip_wb_stb;
reg         xip_wb_cyc;

// spi_top Wishbone interface wires
wire [4:0]  wb_adr;
wire [31:0] wb_dat_i;
wire [31:0] wb_dat_o;
wire        wb_we;
wire        wb_stb;
wire        wb_cyc;
wire        wb_ack;
wire        wb_err;

// XIP active: any state other than IDLE
wire xip_active = (xip_state != S_IDLE);

// Wishbone MUX: IDLE → APB pass-through; XIP → state machine drives
assign wb_adr   = xip_active ? xip_wb_adr   : in_paddr[4:0];
assign wb_dat_i = xip_active ? xip_wb_dat   : in_pwdata;
assign wb_we    = xip_active ? xip_wb_we    : in_pwrite;
assign wb_stb   = xip_active ? xip_wb_stb   : in_psel;
assign wb_cyc   = xip_active ? xip_wb_cyc   : in_penable;

// APB outputs MUX
assign in_pready  = xip_active ? (xip_state == S_DONE) : wb_ack;
assign in_prdata  = xip_active ? xip_rdata : wb_dat_o;
assign in_pslverr = xip_active ? 1'b0 : wb_err;

// Byte-swap function for flash data
function [31:0] bswap32;
  input [31:0] x;
  bswap32 = {x[7:0], x[15:8], x[23:16], x[31:24]};
endfunction

// XIP state machine
always @(posedge clock) begin
  if (reset) begin
    xip_state <= S_IDLE;
    xip_wb_stb <= 1'b0;
    xip_wb_cyc <= 1'b0;
    xip_wb_we  <= 1'b0;
  end else begin
    case (xip_state)
      S_IDLE: begin
        if (is_flash && in_psel && in_penable && !in_pwrite) begin
          xip_addr  <= {in_paddr[23:2], 2'b00};
          xip_state <= S_WR_TX1;
          // Start writing TX1: adr=0x04, dat={0x03, addr[23:2], 2'b00}
          xip_wb_adr <= 5'h04;
          xip_wb_dat <= {8'h03, in_paddr[23:2], 2'b00};
          xip_wb_we  <= 1'b1;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_WR_TX1: begin
        if (wb_ack) begin
          xip_state  <= S_WR_TX0;
          xip_wb_adr <= 5'h00;
          xip_wb_dat <= 32'h0;
          xip_wb_we  <= 1'b1;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_WR_TX0: begin
        if (wb_ack) begin
          xip_state  <= S_WR_SS;
          xip_wb_adr <= 5'h18;
          xip_wb_dat <= 32'h01;
          xip_wb_we  <= 1'b1;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_WR_SS: begin
        if (wb_ack) begin
          xip_state  <= S_WR_CTRL;
          xip_wb_adr <= 5'h10;
          xip_wb_dat <= 32'h00002540; // ASS | TX_NEG | GO | 64
          xip_wb_we  <= 1'b1;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_WR_CTRL: begin
        if (wb_ack) begin
          xip_state  <= S_POLL;
          xip_wb_adr <= 5'h10;
          xip_wb_dat <= 32'h0;
          xip_wb_we  <= 1'b0;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_POLL: begin
        if (wb_ack) begin
          if (wb_dat_o[8]) begin
            // GO still set, keep polling
            xip_wb_stb <= 1'b1;
            xip_wb_cyc <= 1'b1;
          end else begin
            // GO cleared, read RX0
            xip_state  <= S_RD_RX0;
            xip_wb_adr <= 5'h00;
            xip_wb_we  <= 1'b0;
            xip_wb_stb <= 1'b1;
            xip_wb_cyc <= 1'b1;
          end
        end
      end
      S_RD_RX0: begin
        if (wb_ack) begin
          xip_rdata  <= bswap32(wb_dat_o);
          xip_state  <= S_CLR_SS;
          // Write SS=0 to deassert chip select
          xip_wb_adr <= 5'h18;
          xip_wb_dat <= 32'h0;
          xip_wb_we  <= 1'b1;
          xip_wb_stb <= 1'b1;
          xip_wb_cyc <= 1'b1;
        end
      end
      S_CLR_SS: begin
        if (wb_ack) begin
          xip_state  <= S_DONE;
          xip_wb_stb <= 1'b0;
          xip_wb_cyc <= 1'b0;
          xip_wb_we  <= 1'b0;
        end
      end
      S_DONE: begin
        // APB pready=1 this cycle, return to IDLE
        xip_state <= S_IDLE;
      end
      default: begin
        xip_state <= S_IDLE;
      end
    endcase
  end
end

spi_top u0_spi_top (
  .wb_clk_i(clock),
  .wb_rst_i(reset),
  .wb_adr_i(wb_adr),
  .wb_dat_i(wb_dat_i),
  .wb_dat_o(wb_dat_o),
  .wb_sel_i(4'b1111),
  .wb_we_i (wb_we),
  .wb_stb_i(wb_stb),
  .wb_cyc_i(wb_cyc),
  .wb_ack_o(wb_ack),
  .wb_err_o(wb_err),
  .wb_int_o(spi_irq_out),

  .ss_pad_o(spi_ss),
  .sclk_pad_o(spi_sck),
  .mosi_pad_o(spi_mosi),
  .miso_pad_i(spi_miso)
);

`endif // FAST_FLASH

endmodule
