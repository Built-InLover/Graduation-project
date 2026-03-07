module bitrev (
  input  sck,
  input  ss,
  input  mosi,
  output miso
);
  wire reset = ss;
  reg [2:0] cnt;
  reg [7:0] recv, send;
  reg phase; // 0=recv, 1=send

  always @(posedge sck or posedge reset) begin
    if (reset) begin
      cnt   <= 3'd0;
      phase <= 1'b0;
      recv  <= 8'd0;
      send  <= 8'd0;
    end else if (!phase) begin
      recv <= {recv[6:0], mosi};
      if (cnt == 3'd7) begin
        phase <= 1'b1;
        cnt   <= 3'd0;
        send  <= {recv[0], recv[1], recv[2], recv[3], recv[4], recv[5], recv[6], mosi};
      end else begin
        cnt <= cnt + 3'd1;
      end
    end else begin
      send <= {send[6:0], 1'b0};
      cnt  <= cnt + 3'd1;
    end
  end

  assign miso = ss ? 1'b1 : (phase ? send[7] : 1'b1);
endmodule
