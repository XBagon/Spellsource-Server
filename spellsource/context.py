import contextlib
import json
import os
import subprocess
import sys

from py4j.java_gateway import JavaGateway, java_import, CallbackServerParameters


class Context(contextlib.AbstractContextManager):
    APPLICATION_NAME = 'com.hiddenswitch.spellsource.applications.PythonBridge'
    STATUS_READY = 1
    STATUS_ADDRESS_IN_USE = 2
    _LINE_BUFFERED = 1
    
    def __init__(self):
        self._is_closed = False
        # TODO: Do not start multiple JVMs
        self._process = Context._start_jvm_process()
        while self._process.poll() is None:
            msg = self._process.stdout.readline()
            try:
                msg = json.loads(msg)
                if 'status' in msg and msg['status'] == 'ready':
                    self.status = Context.STATUS_READY
                elif 'status' in msg and msg['status'] == 'failed':
                    self.status = Context.STATUS_ADDRESS_IN_USE
                    raise Exception('Address already in use.')
                break
            except json.JSONDecodeError:
                continue
        self._gateway = JavaGateway(callback_server_parameters=CallbackServerParameters())
        
        for name, package in (('game', 'net.demilich.metastone.game.*'),
                              ('entities', 'net.demilich.metastone.game.entities.*'),
                              ('decks', 'net.demilich.metastone.game.decks.*'),
                              ('events', 'net.demilich.metastone.game.events.*'),
                              ('actions', 'net.demilich.metastone.game.actions.*'),
                              ('logic', 'net.demilich.metastone.game.logic.*'),
                              ('cards', 'net.demilich.metastone.game.cards.*'),
                              ('spells', 'net.demilich.metastone.game.spells.*'),
                              ('targeting', 'net.demilich.metastone.game.targeting.*'),
                              ('utils', 'net.demilich.metastone.game.utils.*'),
                              ('behaviour', 'net.demilich.metastone.game.behaviour.*')):
            view = self._gateway.new_jvm_view(name)
            java_import(view, package)
            setattr(self, name, view)
        
        # Include the important classes and enums
        self.GameAction = self.actions.GameAction
        self.Card = self.cards.CardCatalogue
        self.Deck = self.decks.Deck
        self.Entity = self.entities.Entity
        self.Actor = self.entities.Actor
        self.GameEvent = self.events.GameEvent
        self.GameEventType = self.game.events.GameEventType
        self.EntityType = self.entities.EntityType
        self.Weapon = self.entities.Weapons.Weapon
        self.Minion = self.entities.minions.Minion
        self.Hero = self.entities.heroes.Hero
        self.Attribute = self.utils.Attribute
        self.ActionType = self.actions
        self.Rarity = self.cards.Rarity
        self.CardType = self.cards.CardType
        self.CardSet = self.cards.CardSet
        self.GameLogic = self.logic.GameLogic
        self.Zones = self.targeting.Zones
        self.HeroClass = self.entities.heroes.HeroClass
        self.CardCatalogue = self.cards.CardCatalogue
        self.PythonBridge = self._gateway.jvm.com.hiddenswitch.spellsource.applications.PythonBridge
        self.ArrayList = self._gateway.jvm.java.util.ArrayList
        
        self.CardCatalogue.loadCardsFromPackage()
    
    def close(self):
        if not hasattr(self, '_gateway'):
            return
        # self.process.send_signal(signal=signal.SIGINT)
        self._gateway.close()
        self._process.terminate()
        self._is_closed = True
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
    
    def __del__(self):
        if self._is_closed:
            return
        
        self.close()
    
    @staticmethod
    def _find_jar_path():
        """
        Tries to find the path where the Spellsource jar is located.
        """
        paths = []
        jar_file = "net-1.3.0-all.jar"
        paths.append(jar_file)
        # local
        paths.append(os.path.join(os.path.dirname(
            os.path.realpath(__file__)), "../net/build/libs/" + jar_file))
        paths.append(os.path.join(os.path.dirname(
            os.path.realpath(__file__)), "../share/spellsource/" + jar_file))
        paths.append(os.path.join(sys.prefix, "share/spellsource/" + jar_file))
        # pip install py4j # On Ubuntu 16.04, where virtualenvepath=/usr/local
        #   this file is here:
        #     virtualenvpath/lib/pythonX/dist-packages/py4j/java_gateway.py
        #   the jar file is here: virtualenvpath/share/py4j/py4j.jar
        # pip install --user py4j # On Ubuntu 16.04, where virtualenvepath=~/.local
        #   this file is here:
        #     virtualenvpath/lib/pythonX/site-packages/py4j/java_gateway.py
        #   the jar file is here: virtualenvpath/share/py4j/py4j.jar
        paths.append(os.path.join(os.path.dirname(
            os.path.realpath(__file__)), "../../../../share/spellsource/" + jar_file))
        
        for path in paths:
            if os.path.exists(path):
                return path
        return ""
    
    @staticmethod
    def _start_jvm_process():
        return subprocess.Popen(
            ['java', '-Xmx512m', '-cp', Context._find_jar_path(), Context.APPLICATION_NAME],
            stdout=subprocess.PIPE, bufsize=Context._LINE_BUFFERED, universal_newlines=True)
    
    def is_open(self):
        return self._gateway._gateway_client.is_connected
