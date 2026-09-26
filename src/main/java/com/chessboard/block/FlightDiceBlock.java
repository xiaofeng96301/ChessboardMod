package com.chessboard.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 飞行棋骰子模型方块 —— 纯渲染用，从不放置。
 * 方块状态里存朝上的点数，模型按点数取样 {@code flight_dice.png} 图集上对应的那一面。
 */
public class FlightDiceBlock extends Block {

    /** 朝上的点数 1..6 */
    public static final IntegerProperty FACE = IntegerProperty.create("face", 1, 6);

    public FlightDiceBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACE, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACE);
    }
}
