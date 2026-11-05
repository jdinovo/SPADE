# SPADE

## Requirements

- Java 17
- Maven 4.0.0
- Eclipse MOSAIC 25.2
- SUMO 1.28.0

*Ensure Eclipse MOSAIC folder is placed next to the SPADE project folder as shown*
```
├── SPADE
│   ...
│   ├── mosaic
│   ├── README.md
│   └── run_spade.sh
├── eclipse-mosaic-25.2
│   ...
│   └── mosaic.sh
```

MOSAIC scenarios are each within their respective folders. Java application code is contained within the `mosaic` folder.

## Execution

1. Execute permissions `chmod +x ./run_spade.sh`

2. Generation script dry run `DRY_RUN=1 ./run_spade.sh`

3. Run the dataset generation `./run_spade.sh`

4. For run metadata file cleanup use `./run_spade.sh clean`

