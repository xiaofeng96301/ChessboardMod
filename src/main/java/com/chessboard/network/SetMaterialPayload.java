package com.chessboard.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：设置某棋盘上某槽位的棋子材质。
 */
public record SetMaterialPayload(BlockPos pos, int slot, String material) implements CustomPacketPayload {

    public static final Type<SetMaterialPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("chessboard", "set_material"));

    public static final StreamCodec<ByteBuf, SetMaterialPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetMaterialPayload::pos,
            ByteBufCodecs.VAR_INT, SetMaterialPayload::slot,
            ByteBufCodecs.STRING_UTF8, SetMaterialPayload::material,
            SetMaterialPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
