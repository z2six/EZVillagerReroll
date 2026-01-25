# Manual Farming

Manual Farming is an AI module that takes over villager behavior (while active) to farm in a configurable work area.

## Workstation & range

- Manual farming uses a workstation position as its center.
- The villager operates within a range around that workstation.

## Priorities

Manual farming runs actions in priority order:

1. Deposit/withdraw (logistics) if rules trigger
2. Use bonemeal (if enabled and available)
3. Pick up configured drops
4. Plant configured seeds/crops
5. Harvest mature crops
6. Optional: till dirt into farmland (if enabled and a hoe is available)
