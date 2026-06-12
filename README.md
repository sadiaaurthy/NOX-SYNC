# FableOps: Synchronized Survival

## Overview

FableOps: Synchronized Survival is a 2-player cooperative LAN-based top-down cyberpunk survival game developed using Java and libGDX.

The project focuses on synchronized multiplayer gameplay where two players connect through a local network, cooperate to survive enemy encounters, complete objectives, and progress through a shared game world.

This project is being developed as part of the CSE 4402: Visual Programming Lab course.

---

## Features

* 2-player cooperative gameplay
* LAN-based multiplayer synchronization
* Shared game world and objectives
* Real-time player movement and interaction
* Enemy survival encounters
* Modular object-oriented architecture
* Java + libGDX implementation

---

## Project Structure

### core

Contains the shared game logic:

* Game screens
* Player systems
* World management
* Networking models
* Gameplay mechanics

### lwjgl3

Desktop launcher using LWJGL3:

* Application startup
* Desktop-specific configurations
* Resource loading

---

## Technologies Used

* Java
* libGDX
* Gradle
* LWJGL3
* Socket-based LAN Networking

---

## Running the Project

Run the desktop version:

```bash
./gradlew lwjgl3:run
```

Windows:

```bash
gradlew.bat lwjgl3:run
```

Build executable JAR:

```bash
./gradlew lwjgl3:jar
```

Generated JAR:

```text
lwjgl3/build/libs/
```

---

## Team

FableOps Development Team

Islamic University of Technology (IUT)

Department of Computer Science and Engineering

---

## License

This project is developed for academic and educational purposes.
