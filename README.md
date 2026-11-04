# SPADE

## Requirements

- Java 17
- Maven 4.0.0
- Eclipse MOSAIC 25.2
- SUMO 1.28.0

*Ensure Eclipse MOSAIC folder is placed next to the SPADE project folder*
```
├── SPADE
│   ...
│   ├── README.md
│   └── run_spade.sh
├── eclipse-mosaic-25.2
│   ...
│   └── mosaic.sh
```

## Execution

1. Execute permissions `chmod +x ./run_spade.sh`

2. Generation script dry run `DRY_RUN=1 ./run_spade.sh`

3. Run the dataset generation `./run_spade.sh`
>>>>>>> a1b37be (Initial Commit)
