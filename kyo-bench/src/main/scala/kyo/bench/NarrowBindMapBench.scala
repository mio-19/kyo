package kyo.bench

class NarrowBindMapBench extends Bench.SyncAndFork[Int]:

    val depth          = 10000
    val expectedResult = 10000

    def kyoBench() =
        import kyo.*

        def loop(i: Int): Int < IOs =
            if i < depth then
                IOs(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
            else IOs(i)

        IOs(0).flatMap(loop)
    end kyoBench

    def catsBench() =
        import cats.effect.*

        def loop(i: Int): IO[Int] =
            if i < depth then
                IO(i + 11)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .flatMap(loop)
            else IO(i)

        IO(0).flatMap(loop)
    end catsBench

    def zioBench() =
        import zio.*

        def loop(i: Int): UIO[Int] =
            if i < depth then
                ZIO.succeed[Int](i + 11)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .map(_ - 1)
                    .flatMap(loop)
            else ZIO.succeed(i)

        ZIO.succeed(0).flatMap[Any, Nothing, Int](loop)
    end zioBench
end NarrowBindMapBench
