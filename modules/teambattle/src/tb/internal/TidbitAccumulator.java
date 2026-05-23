package tb.internal;

import module java.base;
import module teambattle.api;
import module chariot;
import chariot.model.Enums.Color;

/// And:able tidbits...
/// "About <member>'s win, " + String.join(" and ", tidbits) + "!"
public record TidbitAccumulator() implements Accumulator<InternalEvent.GameResult, TeamBattleEvent> {

    @Override
    public Result<InternalEvent.GameResult, TeamBattleEvent> accept(InternalEvent.GameResult result) {
        return switch (result) {
            case InternalEvent.Win(_,var member,_,_, _,Some(Game game)) when tidbits(game, member) instanceof Some(TeamBattleEvent.Tidbits bits)
                    -> new SelfAndValue<>(this, bits);
            default -> new Self<>(this);
        };
    }

    public static Opt<TeamBattleEvent.Tidbits> tidbits(Game game, String member) {
        if (! (game.winner() instanceof Some(var ourColor))) return Opt.of();

        List<String> messages = Stream.of(
                dirtyFlag(game, ourColor),
                titledOpp(game, ourColor),
                notCastled(game, ourColor),
                littleTimeLeft(game, ourColor)
                )
            .filter(Opt::isPresent)
            .map(Opt::get)
            .toList();

        return messages.isEmpty() ? Opt.of() : Opt.of(new TeamBattleEvent.Tidbits(member, messages));
    }

    static Opt<String> dirtyFlag(Game game, Color ourColor) {
        if (!(game.status() == Enums.Status.outoftime)) return Opt.of();
        Side ourSide = ourColor == Color.white ? Side.white : Side.black;

        if (board(game) instanceof Some(var board)) {
            int ourPoints = piecePoints(board, ourSide);
            int theirPoints = piecePoints(board, ourSide.other());

            if (theirPoints > ourPoints) {
                return Opt.of("some people would have said it was a dirty flag");
            }
        }
        return Opt.of();
    }

    static Opt<String> titledOpp(Game game, Color ourColor) {
        Player opp = ourColor == Color.white ? game.players().black() : game.players().white();
        return switch (opp) {
            case Player.Account account -> account.user().title().map("opponent was flexing their %s title"::formatted);
            default -> Opt.of();
        };
    }

    static Opt<String> notCastled(Game game, Color ourColor) {
        return game.moves() instanceof Some(String moves) && moves.split(" ") instanceof String[] arr
            && IntStream.range(0, arr.length)
            .filter(ply -> ourColor == Color.white ? ply % 2 == 0 : ply % 2 != 0)
            .filter(ply -> arr[ply].contains("O-O"))
            .findFirst().isPresent()
            ? Opt.of() : Opt.of("it was a win without castling");
    }

    static Opt<String> littleTimeLeft(Game game, Color ourColor) {
        if (game.clocks().size() >= 2) {
            int last = game.clocks().reversed().get(0);
            int nextToLast = game.clocks().reversed().get(1);
            int ourTimeLeftCentis = switch(ourColor) {
                case white -> game.clocks().size() % 2 == 0 ? nextToLast : last;
                case black -> game.clocks().size() % 2 == 0 ? last : nextToLast;
            };
            if (ourTimeLeftCentis <= 100) return Opt.of("there was less than 1 second remaining on the clock");
            if (ourTimeLeftCentis <= 200) return Opt.of("there was less than 2 seconds remaining on the clock");
            if (ourTimeLeftCentis <= 300) return Opt.of("there was less than 3 seconds remaining on the clock");
        }
        return Opt.of();
    }

    static int piecePoints(Board board, Side side) {
        return DefaultBoard.of(board).pieces().all(side).stream()
            .map(Square.With::type)
            .mapToInt(p -> switch(p) {
                case pawn -> 1;
                case bishop,
                     knight -> 3;
                case rook -> 5;
                case queen -> 9;
                case king -> 0;
            }).sum();
    }

    static Opt<Board> board(Game game) {
        if (!(game.moves() instanceof Some(String moves))) return Opt.of();
        return switch(game.variant()) {
            case standard -> Opt.of(Board.ofStandard().play(moves));
            case chess960 -> game.initialFen().map(Board::ofChess960).map(b -> b.play(moves));
            case fromPosition -> game.initialFen().map(Board::ofStandard).map(b -> b.play(moves));
            case antichess,
                 atomic,
                 crazyhouse,
                 horde,
                 kingOfTheHill,
                 racingKings,
                 threeCheck -> Opt.of();
        };
    }
}
