import json
import os

import urllib.request
from os import path

import utils
from cardformatter import fix_dict


def main():
    hero_class_mapping = utils.CLASS_MAPPING
    
    request = urllib.request.Request(
        url='https://api.hearthstonejson.com/v1/23966/enUS/cards.json',
        headers={'User-Agent': 'Mozilla/5.0'
                 })
    
    db = json.loads(urllib.request.urlopen(request).read())
    
    # Filter for only witchwood
    db = [x for x in db if 'set' in x and x['set'] == 'GILNEAS' and 'type' in x and x['type'] != 'ENCHANTMENT']
    
    for hs_card in db:
        card_dict = {}
        card_dict['set'] = 'WITCHWOOD'
        card_dict['name'] = hs_card['name']
        card_dict['description'] = '' if 'text' not in hs_card else hs_card['text']
        attributes = {}
        card_dict['attributes'] = attributes
        card_dict['baseManaCost'] = 0 if 'cost' not in hs_card else hs_card['cost']
        card_dict['rarity'] = 'FREE' if 'rarity' not in hs_card else hs_card['rarity']
        card_dict['type'] = hs_card['type']
        card_dict['heroClass'] = hero_class_mapping[hs_card['cardClass']]
        card_dict['collectible'] = False if 'collectible' not in hs_card else hs_card['collectible']
        lower = hs_card['type'].lower()
        if lower == 'minion' and not card_dict['collectible']:
            lower = 'token'
        filename = utils.name_to_id(name=hs_card['name'], card_type=lower)
        if hs_card['type'] == 'MINION':
            card_dict['baseAttack'] = hs_card['attack']
            card_dict['baseHp'] = hs_card['health']
            if 'race' in hs_card:
                card_dict['race'] = hs_card['race']
        if hs_card['type'] == 'SPELL' or hs_card['type'] == 'HERO_POWER':
            card_dict['spell'] = {'class': 'BuffSpell', 'target': 'SELF'}
            card_dict['targetSelection'] = 'NONE'
        
        if 'mechanics' in hs_card:
            mechanics = hs_card['mechanics']
            if 'BATTLECRY' in mechanics:
                card_dict['battlecry'] = {'spell': {'class': 'NullSpell'}, 'targetSelection': 'NONE'}
                attributes['BATTLECRY'] = True
            if 'AURA' in mechanics:
                card_dict['aura'] = {'class': 'BuffAura', 'target': 'FRIENDLY_MINIONS', 'hpBonus': 0, 'attackBonus': 0}
            if 'CANT_ATTACK' in mechanics:
                attributes['CANNOT_ATTACK'] = True
            if 'CHARGE' in mechanics:
                attributes['CHARGE'] = True
            if 'COMBO' in mechanics:
                attributes['COMBO'] = True
                card_dict['battlecry'] = {'spell': {'class': 'NullSpell'}, 'targetSelection': 'NONE'}
            if 'DEATHRATTLE' in mechanics:
                attributes['DEATHRATTLES'] = True
                card_dict['deathrattle'] = {'class': 'NullSpell'}
            if 'DIVINE_SHIELD' in mechanics:
                attributes['DIVINE_SHIELD'] = True
            if 'IMMUNE' in mechanics:
                attributes['IMMUNE'] = True
            if 'POISONOUS' in mechanics:
                attributes['POISONOUS'] = True
            if 'STEALTH' in mechanics:
                attributes['STEALTH'] = True
            if 'TAUNT' in mechanics:
                attributes['TAUNT'] = True
            if 'WINDFURY' in mechanics:
                attributes['WINDFURY'] = True
            if 'RUSH' in mechanics:
                attributes['RUSH'] = True
        
        card_dict['fileFormatVersion'] = 1
        stubs_ = 'src/main/resources/cards/hearthstone/witchwood/stubs/'
        if card_dict['collectible']:
            stubs_ = path.join(stubs_, 'collectible/')
        else:
            stubs_ = path.join(stubs_, 'uncollectible/')
        try:
            os.makedirs(stubs_)
        except:
            pass
        utils.write_card(fix_dict(card_dict), path.join(stubs_, filename + '.json'))


if __name__ == '__main__':
    main()
