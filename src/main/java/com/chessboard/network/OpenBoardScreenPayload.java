package com.chessboard.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：请求打开某个棋盘的皮肤界面。
 *
 * <p>容器菜单是<b>服务端权威</b>的（槽位号、内容、同步都由服务端发起），客户端不能自己
 * {@code setScreen}。所以按住菜单键右键时只能发这个请求，由服务端校验后调
 * {@code openMenu} 把界面推回来。这也是它和以前的 {@code ChessboardScreen} 最大的区别 ——
 * 以前是客户端本地直接开界面。
 */
public record OpenBoardScreenPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OpenBoardScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("chessboard", "open_board_screen"));

    public static final StreamCodec<ByteBuf, OpenBoardScreenPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenBoardScreenPayload::pos,
            OpenBoardScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
