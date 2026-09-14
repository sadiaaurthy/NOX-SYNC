# FableOps: Synchronized Survival

## Overview

FableOps: Synchronized Survival is a 2-player cooperative LAN-based top-down cyberpunk survival game developed using Java and libGDX.

Two players connect over a local network, cooperate to survive enemy encounters, complete objectives, and progress through a shared game world.

This project is being developed as part of the CSE 4402: Visual Programming Lab course.

---

## Features

* 2-player cooperative gameplay
* LAN-based multiplayer synchronization
* Shared game world and objectives
* Real-time player movement and interaction
* Enemy survival encounters
* Java + libGDX implementation

---

## Project Structure

### launcher

The JavaFX main menu: host, join or debug. It connects the two machines, then opens the game window, and comes back when the window closes.

### core

The game itself: levels, players, enemies, collision, inventory, HUD and networking.

### lwjgl3

The desktop game window (LWJGL3 backend). The game's assets are packaged from `assets/`; the `.psd` files are the editable art sources and are not shipped.

---

## Technologies Used

* Java 21
* libGDX
* JavaFX
* Gradle
* Socket-based LAN networking

---

## Running the Project

In VS Code press `Ctrl+Shift+B`, or run:

```bash
./gradlew launcher:run
```

Windows:

```bash
gradlew.bat launcher:run
```

---

## Team

FableOps Development Team

Islamic University of Technology (IUT)

Department of Computer Science and Engineering

---

## License

This project is developed for academic and educational purposes.
