package com.chessboard.block;

import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 棋子模型方块 —— 带材质（MATERIAL）属性，渲染时按玩家配置切换变体模型。
 */
public class ChessPieceBlock extends Block {

    public static final EnumProperty<ChessMaterial> MATERIAL = EnumProperty.create("material", ChessMaterial.class);

    public ChessPieceBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(MATERIAL, ChessMaterial.OAK));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(MATERIAL);
    }
}
