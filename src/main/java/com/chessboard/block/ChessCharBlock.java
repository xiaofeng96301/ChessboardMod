package com.chessboard.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 棋子汉字模型方块 —— 纯渲染用，从不放置。
 * 汉字做成贴图后即可和棋子一同烘焙进区块几何。
 */
public class ChessCharBlock extends Block {

    public static final EnumProperty<ChessChar> CHAR = EnumProperty.create("char", ChessChar.class);

    public ChessCharBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(CHAR, ChessChar.SHUAI));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(CHAR);
    }
}
