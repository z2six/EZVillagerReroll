package org.z2six.villageroverhaul.server;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

final class VillagerNameGenerator {
    private static final int TARGET_FIRST_NAME_COUNT = 4096;
    private static final int TARGET_LAST_NAME_COUNT = 4096;

    private static final String[] CURATED_FIRST_NAMES = {
            "Aelric", "Aldric", "Alaric", "Alwyn", "Ansel", "Aric", "Arlen", "Armand",
            "Baldric", "Beren", "Beric", "Bram", "Brandel", "Brennar", "Caelan", "Cedric",
            "Corin", "Dain", "Darian", "Dariann", "Edric", "Edwin", "Eldric", "Elric",
            "Emeric", "Eogan", "Evander", "Faelan", "Fenric", "Fintan", "Galen", "Gareth",
            "Garrick", "Godric", "Hadrian", "Halric", "Iagan", "Ivor", "Jareth", "Kael",
            "Leoric", "Lucan", "Lysander", "Merric", "Niall", "Orrin", "Osric", "Percival",
            "Quentin", "Roderic", "Rowan", "Soren", "Tarian", "Theron", "Tristan", "Ulric",
            "Vaelin", "Wulfric", "Yrden", "Aislin", "Alena", "Alinor", "Amara", "Anwen",
            "Arabella", "Aveline", "Beatrice", "Briallen", "Brienna", "Caelia", "Catrin", "Celesse",
            "Clarimond", "Damaris", "Delphine", "Eira", "Elara", "Elenora", "Elowen", "Emmeline",
            "Eowyn", "Fiora", "Gwendolyn", "Helena", "Ilyana", "Isolde", "Jessamine", "Kaelin",
            "Lavinia", "Leora", "Liora", "Lyra", "Mabyn", "Maelis", "Maris", "Melisande",
            "Mirelle", "Nerys", "Odette", "Oriane", "Rosalind", "Rowena", "Sabine", "Selene",
            "Seraphine", "Sylvaine", "Thalia", "Valeria", "Verena", "Vesper", "Yseult", "Ysella",
            "Aeron", "Alder", "Amon", "Arden", "Bastian", "Cassian", "Cyran", "Darianel",
            "Eldon", "Fenris", "Gideon", "Hadar", "Isen", "Jorren", "Kieran", "Lorcan",
            "Marek", "Nicen", "Orlan", "Peregrin", "Quillan", "Ronan", "Stellan", "Torin",
            "Ariadne", "Briona", "Calista", "Daphne", "Elysia", "Freya", "Giselle", "Honora",
            "Illyria", "Jocelyn", "Kerensa", "Luneth", "Meridia", "Nimue", "Ophelia", "Petra",
            "Aderyn", "Althea", "Aravel", "Astrid", "Avalyn", "Bellamy", "Beryl", "Briar",
            "Calanthe", "Celwyn", "Della", "Edda", "Elspeth", "Esme", "Farin", "Fenella",
            "Gilda", "Halwen", "Idris", "Iona", "Jessa", "Keira", "Laurel", "Linnea",
            "Maera", "Maeve", "Mirren", "Nelda", "Odelia", "Orla", "Perrin", "Rhea",
            "Sancia", "Seren", "Tilda", "Vaila", "Willa", "Wynne", "Ylva", "Zephyra",
            "Alden", "Borin", "Caedmon", "Dorian", "Ewan", "Finley", "Hollis", "Jory",
            "Linden", "Marlow", "Oren", "Quincy", "Riven", "Silas", "Tobin", "Wystan"
    };

    private static final String[] CURATED_LAST_NAMES = {
            "Amberhide", "Ashbourne", "Ashfall", "Ashford", "Ashmere", "Blackbriar", "Blackbrook", "Blackmere",
            "Blackthorn", "Bleakridge", "Bluebrook", "Brightwood", "Briarfell", "Briarheart", "Briarmere", "Brighthollow",
            "Bronzeward", "Caskbow", "Cindercrest", "Cinderfall", "Crowfield", "Daleguard", "Dawnmere", "Dawnridge",
            "Deepwell", "Dewmere", "Duskbane", "Duskbrook", "Dunmere", "Eaglecrest", "Ebonbrook", "Ebonmere",
            "Evenwood", "Fairbrook", "Falconer", "Fallowmere", "Fernvale", "Firecrest", "Flintward", "Fogmere",
            "Foxglove", "Frostbrook", "Frostmere", "Glenhaven", "Goldbrook", "Goldmere", "Grayhaven", "Graythorn",
            "Greenbottle", "Greenbrook", "Greengate", "Grimward", "Hartwell", "Hawkridge", "Hazelmere", "Highmere",
            "Hillshade", "Holmwood", "Ironbriar", "Ironbrook", "Ironmere", "Kingsley", "Larkspur", "Lightfoot",
            "Lowmere", "Marblebrook", "Merefield", "Mistbrook", "Mistmere", "Moonbrook", "Mournvale", "Nettlewick",
            "Nightbrook", "Nightmere", "Oakenshield", "Oakheart", "Oakmere", "Pineward", "Pyrebrook", "Quickwater",
            "Ravencrest", "Ravenmere", "Redbrook", "Redmere", "Reedfield", "Riverbend", "Rivermere", "Rosebrook",
            "Rowanfield", "Runehart", "Silverbrook", "Silvermere", "Skylark", "Snowmere", "Starling", "Stillwater",
            "Stonefield", "Stoneward", "Stormbrook", "Stormmere", "Sunfield", "Swiftbrook", "Thornfield", "Thornwall",
            "Timbermere", "Valeguard", "Valewood", "Westerfield", "Westmere", "Whitebriar", "Whitebrook", "Whitevale",
            "Wildmere", "Willowbrook", "Willowmere", "Windmere", "Wintermere", "Wolfden", "Wolfsbane", "Wrenfield",
            "Brighthelm", "Coldstream", "Darkwater", "Dawnforge", "Duskwharf", "Eaglewatch", "Eastmere", "Elderbrook",
            "Emberfall", "Farrowmere", "Featherstone", "Fennwatch", "Frostfall", "Glowmere", "Greymantle", "Hallowmere",
            "Hearthmere", "Highwall", "Hollowmere", "Ivorymere", "Juniper", "Kingsbrook", "Lantern", "Mapleford",
            "Mossbrook", "Northmere", "Rimeward", "Shadowbrook", "Southmere", "Thistlewick", "Umbermoor", "Westwatch",
            "Applebrook", "Barleyfield", "Bellweather", "Birchhaven", "Cloverwick", "Copperfield", "Dewfall", "Emberwick",
            "Farrowfield", "Fennelbrook", "Goldenvale", "Hearthfield", "Honeywick", "Ivybrook", "Lanternwick", "Meadowbrook",
            "Millstone", "Mossvale", "Orchardwell", "Pepperfield", "Plumfield", "Rootwhistle", "Sagewick", "Saltmere",
            "Seedwell", "Sprucemere", "Starfall", "Sunmeadow", "Thatchwood", "Wheatbrook", "Wheatfield", "Wildbriar",
            "Woodsmoke", "Yewbrook", "Belltower", "Brightmill", "Cloverfield", "Fairweather", "Goodbarrel", "Greenmantle",
            "Hearthsong", "Kindlewick", "Mirthwell", "Oakbarrel", "Proudfellow", "Quietbrook", "Warmhearth", "Wellspring"
    };

    private static final String[] FIRST_PREFIXES = {
            "Ael", "Aer", "Ald", "Alth", "Alv", "Am", "An", "Ar",
            "Arn", "Ast", "Ath", "Auv", "Bal", "Bel", "Ber", "Bran",
            "Bren", "Brin", "Bryn", "Cael", "Cal", "Car", "Cas", "Ced",
            "Cer", "Cor", "Cyr", "Dae", "Dael", "Dal", "Dar", "Del",
            "Dor", "Drav", "Ed", "Eld", "El", "Em", "Er", "Ev",
            "Fae", "Fal", "Fen", "Fin", "Gal", "Gar", "Gav", "Gil",
            "Gor", "Hal", "Hel", "Her", "Ily", "Is", "Ith", "Jar",
            "Jor", "Kael", "Kal", "Kel", "Ker", "Lae", "Lar", "Len",
            "Lor", "Luc", "Lys", "Mae", "Mal", "Mar", "Mer", "Mor",
            "Nae", "Nal", "Ner", "Nym", "Or", "Os", "Per", "Quin",
            "Rae", "Ren", "Riv", "Ro", "Sar", "Sel", "Ser", "Syl",
            "Tae", "Tal", "Tar", "Ther", "Tor", "Ul", "Uri", "Vael",
            "Val", "Var", "Ver", "Vor", "Wyn", "Yor", "Ys", "Yva",
            "Ader", "Bell", "Bri", "Clo", "Dun", "Edda", "Els", "Fenn",
            "Holl", "Iona", "Jas", "Keir", "Lin", "Marl", "Odel", "Perr",
            "Ser", "Tild", "Wes", "Zev"
    };

    private static final String[] FIRST_MIDDLES = {
            "a", "ae", "ai", "al", "an", "ar", "e", "ea",
            "ei", "el", "en", "er", "i", "ia", "ie", "il",
            "in", "ir", "o", "oa", "oi", "ol", "on", "or",
            "u", "ul", "un", "ur", "y", "yl", "yn"
    };

    private static final String[] FIRST_SUFFIXES = {
            "ad", "ael", "aen", "aeth", "ain", "al", "am", "an",
            "ar", "ard", "as", "ath", "av", "ayn", "ea", "ela",
            "el", "en", "ena", "enor", "er", "erin", "ess", "eth",
            "ia", "ian", "iel", "ik", "il", "in", "ion", "ir",
            "is", "ith", "iv", "lar", "len", "lia", "line", "lon",
            "lor", "lys", "mir", "na", "ner", "or", "os", "ric",
            "rid", "rin", "ron", "ser", "sian", "ta", "ter", "thas",
            "ther", "ton", "tyr", "var", "vel", "vian", "win", "wyr",
            "wyn", "wen", "well", "wick", "ley", "low", "nell", "rell",
            "yn"
    };

    private static final String[] LAST_PREFIXES = {
            "Amber", "Ash", "Autumn", "Battle", "Black", "Blue", "Bright", "Briar",
            "Bronze", "Candle", "Cedar", "Cinder", "Cloud", "Cold", "Copper", "Crow",
            "Dawn", "Deep", "Dusk", "East", "Elder", "Ember", "Even", "Fair",
            "Falcon", "Far", "Feather", "Fern", "Fire", "Flint", "Fog", "Fox",
            "Frost", "Gold", "Grace", "Grand", "Gray", "Green", "Grim", "Hart",
            "Hawk", "Hazel", "Hearth", "High", "Hill", "Hollow", "Ice", "Iron",
            "Ivory", "Kings", "Lake", "Lark", "Light", "Lone", "Low", "Maple",
            "Marsh", "Mead", "Mist", "Moon", "Moss", "Night", "North", "Oak",
            "Pine", "Quick", "Raven", "Red", "Reed", "River", "Rose", "Rowan",
            "Rune", "Shadow", "Silver", "Sky", "Snow", "South", "Star", "Still",
            "Stone", "Storm", "Summer", "Sun", "Swift", "Thorn", "Timber", "Umber",
            "Vale", "West", "White", "Wild", "Willow", "Wind", "Winter", "Wolf",
            "Apple", "Barley", "Bell", "Birch", "Clover", "Fennel", "Honey", "Ivy",
            "Meadow", "Mill", "Orchard", "Pepper", "Plum", "Sage", "Salt", "Seed",
            "Spruce", "Thatch", "Wheat", "Wood", "Yew", "Wren"
    };

    private static final String[] LAST_LINKERS = {
            "", "", "", "en", "er", "in", "ing", "el",
            "em", "on", "or"
    };

    private static final String[] LAST_SUFFIXES = {
            "bane", "barrow", "beck", "blade", "bloom", "born", "bough", "brand",
            "brook", "briar", "crest", "crown", "dale", "den", "field", "fire",
            "ford", "forge", "gate", "glen", "guard", "hall", "haven", "hearth",
            "helm", "hold", "hollow", "keep", "lea", "light", "mark", "meadow",
            "mere", "moor", "peak", "reach", "ridge", "ring", "run", "shade",
            "shaw", "shield", "song", "spire", "spring", "stone", "thorn", "vale",
            "ward", "watch", "water", "well", "wharf", "wick", "wild", "wall",
            "bell", "binder", "blossom", "bower", "branch", "brew", "croft", "fall",
            "fen", "grove", "mill", "root", "seed", "smoke", "stead", "whistle",
            "yard", "wind", "wood", "worth", "wright"
    };

    private static final String[] FIRST_NAMES = buildFirstNames();
    private static final String[] LAST_NAMES = buildLastNames();

    private VillagerNameGenerator() {
    }

    static String createName(UUID villagerId) {
        return createName(createFirstName(villagerId), createLastName(villagerId));
    }

    static String createFirstName(UUID villagerId) {
        long firstSeed = mix64(villagerId.getMostSignificantBits() ^ villagerId.getLeastSignificantBits());
        return FIRST_NAMES[(int) Long.remainderUnsigned(firstSeed, FIRST_NAMES.length)];
    }

    static String createLastName(UUID villagerId) {
        long lastSeed = mix64(Long.rotateLeft(villagerId.getLeastSignificantBits(), 17) ^ 0x9E3779B97F4A7C15L);
        return LAST_NAMES[(int) Long.remainderUnsigned(lastSeed, LAST_NAMES.length)];
    }

    static String createName(String firstName, String lastName) {
        return firstName + ' ' + lastName;
    }

    @Nullable
    static String extractLastName(@Nullable String fullName) {
        if (fullName == null) {
            return null;
        }

        int separator = fullName.indexOf(' ');
        if (separator < 1 || separator >= fullName.length() - 1) {
            return null;
        }

        String lastName = fullName.substring(separator + 1).trim();
        return lastName.isEmpty() ? null : lastName;
    }

    private static String[] buildFirstNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(TARGET_FIRST_NAME_COUNT * 2);
        addAll(names, CURATED_FIRST_NAMES);
        addGeneratedFirstNames(names);
        return names.toArray(String[]::new);
    }

    private static String[] buildLastNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(TARGET_LAST_NAME_COUNT * 2);
        addAll(names, CURATED_LAST_NAMES);
        addGeneratedLastNames(names);
        return names.toArray(String[]::new);
    }

    private static void addGeneratedFirstNames(Set<String> names) {
        for (String prefix : FIRST_PREFIXES) {
            for (String suffix : FIRST_SUFFIXES) {
                if (names.size() >= TARGET_FIRST_NAME_COUNT) {
                    return;
                }

                String candidate = joinFlowing(prefix, suffix);
                if (isValidFirstName(candidate)) {
                    names.add(candidate);
                }
            }
        }

        for (String prefix : FIRST_PREFIXES) {
            for (String middle : FIRST_MIDDLES) {
                for (String suffix : FIRST_SUFFIXES) {
                    if (names.size() >= TARGET_FIRST_NAME_COUNT) {
                        return;
                    }

                    String candidate = joinFlowing(joinFlowing(prefix, middle), suffix);
                    if (isValidFirstName(candidate)) {
                        names.add(candidate);
                    }
                }
            }
        }
    }

    private static void addGeneratedLastNames(Set<String> names) {
        for (String prefix : LAST_PREFIXES) {
            for (String suffix : LAST_SUFFIXES) {
                if (names.size() >= TARGET_LAST_NAME_COUNT) {
                    return;
                }

                String candidate = joinCompound(prefix, suffix);
                if (isValidLastName(candidate) && !prefix.equalsIgnoreCase(suffix)) {
                    names.add(candidate);
                }
            }
        }

        for (String prefix : LAST_PREFIXES) {
            for (String linker : LAST_LINKERS) {
                for (String suffix : LAST_SUFFIXES) {
                    if (names.size() >= TARGET_LAST_NAME_COUNT) {
                        return;
                    }

                    String candidate = joinCompound(joinCompound(prefix, linker), suffix);
                    if (isValidLastName(candidate) && !prefix.equalsIgnoreCase(suffix)) {
                        names.add(candidate);
                    }
                }
            }
        }
    }

    private static void addAll(Set<String> names, String[] values) {
        for (String value : values) {
            names.add(value);
        }
    }

    private static String joinFlowing(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }

        char leftLast = left.charAt(left.length() - 1);
        char rightFirst = right.charAt(0);
        if (Character.toLowerCase(leftLast) == Character.toLowerCase(rightFirst)) {
            return left + right.substring(1);
        }
        return left + right;
    }

    private static String joinCompound(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }

        char leftLast = left.charAt(left.length() - 1);
        char rightFirst = right.charAt(0);
        if (Character.toLowerCase(leftLast) == Character.toLowerCase(rightFirst) && isVowel(leftLast)) {
            return left + right.substring(1);
        }
        return left + right;
    }

    private static boolean isValidFirstName(String value) {
        return isValidName(value, 4, 12) && !containsAwkwardDoubleVowel(value);
    }

    private static boolean isValidLastName(String value) {
        return isValidName(value, 5, 16);
    }

    private static boolean isValidName(String value, int minLength, int maxLength) {
        return value.length() >= minLength
                && value.length() <= maxLength
                && containsVowel(value)
                && !hasTripleRepeat(value)
                && !hasLongVowelRun(value);
    }

    private static boolean containsVowel(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isVowel(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTripleRepeat(String value) {
        for (int i = 2; i < value.length(); i++) {
            char current = Character.toLowerCase(value.charAt(i));
            if (current == Character.toLowerCase(value.charAt(i - 1))
                    && current == Character.toLowerCase(value.charAt(i - 2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLongVowelRun(String value) {
        int runLength = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isVowel(value.charAt(i))) {
                runLength++;
                if (runLength > 3) {
                    return true;
                }
            } else {
                runLength = 0;
            }
        }
        return false;
    }

    private static boolean containsAwkwardDoubleVowel(String value) {
        String lower = value.toLowerCase();
        return lower.contains("aa")
                || lower.contains("ee")
                || lower.contains("ii")
                || lower.contains("uu")
                || lower.contains("yy");
    }

    private static boolean isVowel(char value) {
        return switch (Character.toLowerCase(value)) {
            case 'a', 'e', 'i', 'o', 'u', 'y' -> true;
            default -> false;
        };
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }
}
