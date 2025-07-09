# Rank API

The Rank API module provides the core interfaces and models for the rank system in Fulcrum.

## Structure

- `model/` - Contains rank-related data models (e.g., MonthlyRankData)
- `enums/` - Contains rank enumerations (e.g., PackageRank, RankType)
- `events/` - Contains rank-related events (e.g., RankChangeEvent, RankExpireEvent)

## Dependencies

- Paper API - For Bukkit/Paper types
- data-api - For data annotations used in rank models

## Usage

This module will be used by:
- player-core - For rank system implementation
- Other modules that need to interact with the rank system