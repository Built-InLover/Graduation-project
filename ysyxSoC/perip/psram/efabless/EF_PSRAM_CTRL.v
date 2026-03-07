/*
	Copyright 2020 Efabless Corp.

	Author: Mohamed Shalan (mshalan@efabless.com)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at:
	http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/
/*
    QPI PSRAM Controller (upgraded from QSPI)

    Adds PSRAM_INIT module: sends 35h (Enter QPI) via 1-bit SPI on reset.
    After init, PSRAM_READER and PSRAM_WRITER use QPI (4-bit CMD).

    QPI timing vs QSPI:
      READER: CMD 2 cycles (was 8), FINAL_COUNT = 13+size*2 (was 19+size*2)
      WRITER: CMD 2 cycles (was 8), FINAL_COUNT =  7+size*2 (was 13+size*2)
*/

`timescale              1ns/1ps
`default_nettype        none

// -----------------------------------------------------------------------
// PSRAM_INIT: 1-bit SPI 发送 35h（Enter QPI Mode），完成后 done=1
// -----------------------------------------------------------------------
module PSRAM_INIT (
    input   wire        clk,
    input   wire        rst_n,
    input   wire        start,
    output  wire        done,

    output  reg         sck,
    output  reg         ce_n,
    output  wire [3:0]  dout,
    output  wire        douten
);
    localparam  IDLE = 1'b0,
                INIT = 1'b1;

    wire [7:0]  CMD_35H = 8'h35;

    reg         state;
    reg [7:0]   counter;

    always @ (posedge clk or negedge rst_n)
        if (!rst_n) state <= IDLE;
        else if (state == IDLE && start) state <= INIT;
        else if (state == INIT && done)  state <= IDLE;

    // sck @ clk/2
    always @ (posedge clk or negedge rst_n)
        if (!rst_n)       sck <= 1'b0;
        else if (~ce_n)   sck <= ~sck;
        else              sck <= 1'b0;

    // ce_n
    always @ (posedge clk or negedge rst_n)
        if (!rst_n)             ce_n <= 1'b1;
        else if (state == INIT) ce_n <= 1'b0;
        else                    ce_n <= 1'b1;

    // counter 在 sck posedge 递增
    always @ (posedge clk or negedge rst_n)
        if (!rst_n)              counter <= 8'b0;
        else if (sck & ~done)    counter <= counter + 1'b1;
        else if (state == IDLE)  counter <= 8'b0;

    // 1-bit 串行输出 35h，MSB first
    assign dout   = {3'b0, CMD_35H[7 - counter[2:0]]};
    assign douten = (state == INIT);
    assign done   = (counter == 8'd8);

endmodule

// -----------------------------------------------------------------------
// PSRAM_READER: QPI 版（CMD 4-bit/cycle，2 cycle）
// counter 0~1:  CMD EBh，4-bit/cycle，douten=1
// counter 2~7:  ADDR 24-bit，4-bit/cycle，douten=1
// counter 8~13: DUMMY 6 cycle，douten=0
// counter 14~21: DATA 32-bit，4-bit/cycle，颗粒驱动 din
// FINAL_COUNT = 13 + size*2
// -----------------------------------------------------------------------
module PSRAM_READER (
    input   wire            clk,
    input   wire            rst_n,
    input   wire [23:0]     addr,
    input   wire            rd,
    input   wire [2:0]      size,
    output  wire            done,
    output  wire [31:0]     line,

    output  reg             sck,
    output  reg             ce_n,
    input   wire [3:0]      din,
    output  wire [3:0]      dout,
    output  wire            douten
);

    localparam  IDLE = 1'b0,
                READ = 1'b1;

    wire [7:0]  FINAL_COUNT = 13 + size*2;

    reg         state, nstate;
    reg [7:0]   counter;
    reg [23:0]  saddr;
    reg [7:0]   data [3:0];

    wire [7:0]  CMD_EBH = 8'heb;

    always @*
        case (state)
            IDLE: if(rd) nstate = READ; else nstate = IDLE;
            READ: if(done) nstate = IDLE; else nstate = READ;
        endcase

    always @ (posedge clk or negedge rst_n)
        if(!rst_n) state <= IDLE;
        else state <= nstate;

    // sck @ clk/2
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            sck <= 1'b0;
        else if(~ce_n)
            sck <= ~sck;
        else if(state == IDLE)
            sck <= 1'b0;

    // ce_n
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            ce_n <= 1'b1;
        else if(state == READ)
            ce_n <= 1'b0;
        else
            ce_n <= 1'b1;

    // counter 在 sck posedge 递增
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            counter <= 8'b0;
        else if(sck & ~done)
            counter <= counter + 1'b1;
        else if(state == IDLE)
            counter <= 8'b0;

    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            saddr <= 24'b0;
        else if((state == IDLE) && rd)
            saddr <= {addr[23:0]};

    // 采样读数据（counter 14~FINAL_COUNT，sck posedge）
    wire [1:0] byte_index = {counter[7:1] - 8'd7}[1:0]; // counter=14→byte0, 16→byte1...
    always @ (posedge clk)
        if(counter >= 14 && counter <= FINAL_COUNT)
            if(sck)
                data[byte_index] <= {data[byte_index][3:0], din};

    // QPI CMD：4-bit/cycle，2 cycle（counter 0~1）
    // counter=0: EBh[7:4], counter=1: EBh[3:0]
    assign dout =   (counter == 0)  ?   CMD_EBH[7:4]    :
                    (counter == 1)  ?   CMD_EBH[3:0]    :
                    (counter == 2)  ?   saddr[23:20]    :
                    (counter == 3)  ?   saddr[19:16]    :
                    (counter == 4)  ?   saddr[15:12]    :
                    (counter == 5)  ?   saddr[11:8]     :
                    (counter == 6)  ?   saddr[7:4]      :
                    (counter == 7)  ?   saddr[3:0]      :
                    4'h0;

    assign douten   = (counter < 8);
    assign done     = (counter == FINAL_COUNT + 1);

    generate
        genvar i;
        for(i=0; i<4; i=i+1)
            assign line[i*8+7: i*8] = data[i];
    endgenerate

endmodule

// -----------------------------------------------------------------------
// PSRAM_WRITER: QPI 版（CMD 4-bit/cycle，2 cycle）
// counter 0~1:  CMD 38h，4-bit/cycle，douten=1
// counter 2~7:  ADDR 24-bit，4-bit/cycle，douten=1
// counter 8~15: DATA 32-bit，4-bit/cycle，douten=1
// FINAL_COUNT = 7 + size*2
// -----------------------------------------------------------------------
module PSRAM_WRITER (
    input   wire            clk,
    input   wire            rst_n,
    input   wire [23:0]     addr,
    input   wire [31: 0]    line,
    input   wire [2:0]      size,
    input   wire            wr,
    output  wire            done,

    output  reg             sck,
    output  reg             ce_n,
    input   wire [3:0]      din,
    output  wire [3:0]      dout,
    output  wire            douten
);
    localparam  IDLE = 1'b0,
                WRITE = 1'b1;

    wire [7:0]  FINAL_COUNT = 7 + size*2;

    reg         state, nstate;
    reg [7:0]   counter;
    reg [23:0]  saddr;

    wire [7:0]  CMD_38H = 8'h38;

    always @*
        case (state)
            IDLE: if(wr) nstate = WRITE; else nstate = IDLE;
            WRITE: if(done) nstate = IDLE; else nstate = WRITE;
        endcase

    always @ (posedge clk or negedge rst_n)
        if(!rst_n) state <= IDLE;
        else state <= nstate;

    // sck @ clk/2
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            sck <= 1'b0;
        else if(~ce_n)
            sck <= ~sck;
        else if(state == IDLE)
            sck <= 1'b0;

    // ce_n
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            ce_n <= 1'b1;
        else if(state == WRITE)
            ce_n <= 1'b0;
        else
            ce_n <= 1'b1;

    // counter 在 sck posedge 递增
    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            counter <= 8'b0;
        else if(sck & ~done)
            counter <= counter + 1'b1;
        else if(state == IDLE)
            counter <= 8'b0;

    always @ (posedge clk or negedge rst_n)
        if(!rst_n)
            saddr <= 24'b0;
        else if((state == IDLE) && wr)
            saddr <= addr;

    // QPI CMD：4-bit/cycle，2 cycle（counter 0~1）
    // 数据字节序与 QSPI 版相同（counter 8~15 对应 QSPI counter 14~21）
    assign dout =   (counter == 0)  ?   CMD_38H[7:4]    :
                    (counter == 1)  ?   CMD_38H[3:0]    :
                    (counter == 2)  ?   saddr[23:20]    :
                    (counter == 3)  ?   saddr[19:16]    :
                    (counter == 4)  ?   saddr[15:12]    :
                    (counter == 5)  ?   saddr[11:8]     :
                    (counter == 6)  ?   saddr[7:4]      :
                    (counter == 7)  ?   saddr[3:0]      :
                    (counter == 8)  ?   line[7:4]       :
                    (counter == 9)  ?   line[3:0]       :
                    (counter == 10) ?   line[15:12]     :
                    (counter == 11) ?   line[11:8]      :
                    (counter == 12) ?   line[23:20]     :
                    (counter == 13) ?   line[19:16]     :
                    (counter == 14) ?   line[31:28]     :
                    line[27:24];

    assign douten   = (~ce_n);
    assign done     = (counter == FINAL_COUNT + 1);

endmodule
