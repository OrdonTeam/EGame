package io.ordonteam.egame

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class EGameApplication

fun main(args: Array<String>) {
    runApplication<EGameApplication>(*args)
}

@RestController
class EController(val repository: ERepository) {

    @GetMapping("/state")
    fun gameState() = onState("") {}

    @GetMapping("/start")
    fun start(sender: String) = onState(sender) { it.start() }

    @GetMapping("/build")
    fun build(sender: String) = onState(sender) { it.build() }

    @GetMapping("/updateBalance")
    fun updateBalance(sender: String) = onState(sender) { it.updateBalance() }

    @GetMapping("/summonFleet")
    fun summonFleet(sender: String, size: Long) = onState(sender) { it.summonFleet(size) }

    @GetMapping("/sendFleet")
    fun sendFleet(sender: String, target: String) = onState(sender) { it.sendFleet(target) }

    @GetMapping("/battle")
    fun battle(sender: String, attacker: String, defender: String) = onState(sender) { it.battle(attacker, defender) }

    @GetMapping("/buildingPrice")
    fun buildingPrice(level: Long) = view { it.buildingPrice(level) }

    private fun onState(sender: String, block: (EGame) -> Unit): GameState {
        val state = repository.findAll().first()
        EGame(state, Block(System.currentTimeMillis() / 10_000), Message(sender)).let(block)
        repository.deleteAll()
        repository.save(state)
        return state
    }

    private fun <T> view(block: (EGame) -> T): T {
        val state = repository.findAll().first()
        return EGame(state, Block(System.currentTimeMillis() / 10_000), Message(null)).let(block)
    }
}

@Component
class GameInitializer(val repository: ERepository) : CommandLineRunner {
    override fun run(vararg args: String?) {
        if (repository.count() == 0L) {
            repository.save(GameState(
                    balances = HashMap(),
                    balancesUpdate = HashMap(),
                    buildings = HashMap(),
                    fleets = HashMap()
            ))
        }
    }
}


class EGame(private val state: GameState, private val block: Block, private val msg: Message) {
    fun start() {
        state.balances[msg.sender] = 0L
        state.balancesUpdate[msg.sender] = block.number
        state.buildings[msg.sender] = 20L
        state.fleets[msg.sender] = Fleet(position = msg.sender, size = 0, orbitingTime = 0, landingTime = 0)
    }

    fun build() {
        val level = state.buildings[msg.sender]!!
        if (spend(buildingPrice(level))) {
            state.buildings[msg.sender] = level + 1
        }
    }

    private fun spend(price: Long): Boolean {
        updateBalance()
        val balance = state.balances[msg.sender]!!
        val hasSufficientFounds = price <= balance
        if (hasSufficientFounds) {
            state.balances[msg.sender] = balance - price
        }
        return hasSufficientFounds
    }

    fun buildingPrice(level: Long): Long {
        return Math.pow(6.0 / 5, level.toDouble()).toLong()
    }

    fun updateBalance() {
        updateUserBalance(msg.sender)
    }

    private fun updateUserBalance(user: String): Long {
        val number = block.number
        val increase = (number - state.balancesUpdate[user]!!) * state.buildings[user]!!
        val newBalance = state.balances[user]!! + increase
        state.balances[user] = newBalance
        state.balancesUpdate[user] = number
        return newBalance
    }

    fun summonFleet(size: Long) {
        val fleet = state.fleets[msg.sender]!!
        assert(fleet.position == msg.sender)
        assert(fleet.orbitingTime <= block.number)
        if (spend(size)) {
            state.fleets[msg.sender] = Fleet(
                    position = msg.sender,
                    size = fleet.size + size,
                    orbitingTime = block.number + 10,
                    landingTime = block.number + 20
            )
        }
    }

    fun sendFleet(target: String) {
        val fleet = state.fleets[msg.sender]!!
        assert(fleet.orbitingTime <= block.number)
        state.fleets[msg.sender] = Fleet(
                position = target,
                size = fleet.size,
                orbitingTime = block.number + 10,
                landingTime = block.number + 20
        )
    }

    fun battle(attacker: String, defender: String) {
        val attackerFleet = state.fleets[attacker]!!
        val defenderFleet = state.fleets[defender]!!
        assert(attackerFleet.position == defender)
        assert(attackerFleet.landingTime >= block.number)
        if (defenderFleet.position == defender && defenderFleet.landingTime >= block.number) {
            //defender present
            val defenderSurvived = decreaseFleetSize(defenderFleet.size, attackerFleet.size)
            val attackerSurvived = decreaseFleetSize(attackerFleet.size, defenderFleet.size)
            if (defenderSurvived == 0L) {
                transfer(defender, attacker, attackerSurvived)
            }
            state.fleets[defender] = Fleet(
                    position = defender,
                    size = defenderSurvived,
                    orbitingTime = block.number + 10,
                    landingTime = block.number + 20
            )
            state.fleets[attacker] = Fleet(
                    position = attacker,
                    size = attackerSurvived,
                    orbitingTime = block.number + 10,
                    landingTime = block.number + 20
            )
        } else {
            //defender absent
            transfer(defender, attacker, attackerFleet.size)
            state.fleets[attacker] = Fleet(
                    position = attacker,
                    size = attackerFleet.size,
                    orbitingTime = block.number + 10,
                    landingTime = block.number + 20
            )
        }
    }

    private fun decreaseFleetSize(size: Long, opponentSize: Long): Long {
        return maxOf(0, minOf(size, size + size - opponentSize))
    }

    private fun transfer(from: String, to: String, max: Long) {
        val fromBalance = updateUserBalance(from)
        val toBalance = updateUserBalance(to)
        val transfer = Math.min(fromBalance, max)
        state.balances[to] = toBalance - transfer
        state.balances[from] = fromBalance + transfer
    }
}

data class Block(val number: Long)

data class Message(private val nullableSender: String?) {
    val sender: String by lazy { nullableSender ?: throw RuntimeException("No sender") }
}

data class GameState(
        @Id
        val _id: String? = null,
        val balances: MutableMap<String, Long>,
        val balancesUpdate: MutableMap<String, Long>,
        val buildings: MutableMap<String, Long>,
        val fleets: MutableMap<String, Fleet>
)

data class Fleet(
        val position: String,
        val size: Long,
        val orbitingTime: Long,
        val landingTime: Long
)

interface ERepository : MongoRepository<GameState, String>
